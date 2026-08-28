"""
proto.py — Driver di test per il protocollo Connections (TCP JSON-line).

Parla direttamente il wire protocol (REQUEST -> riga JSON + '\\n', risposta =
riga JSON). Nessuna dipendenza: solo socket + json + threading di stdlib.

Include:
  - TestClient: connessione TCP raw, helper call(op, **fields) e wrapper
    per le 9 operazioni del protocollo.
  - start_test_server(): avvia il server reale su una config temporanea
    (porte alta + file persist/history in /tmp), pronto per i test senza
    toccare data/users.json.
  - VERIFY / SECTION: micro-framework di assertion (nessun framework esterno).
"""
import json
import os
import socket
import subprocess
import threading
import time

HOST = "127.0.0.1"


class TestClient:
    """Un client che parla il protocollo raw su una connessione TCP persistente."""

    def __init__(self, port, udp_port=40000):
        self.sock = socket.create_connection((HOST, port), timeout=10)
        self.sock.settimeout(10)
        self.buf = b""
        self.udp_port = udp_port
        self.last = None  # ultima risposta decodificata

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()

    # -- basso livello ------------------------------------------------------
    def send_raw(self, raw_bytes):
        """Invia bytes grezzi (per testare JSON malformato)."""
        self.sock.sendall(raw_bytes)

    def read(self):
        """Legge un'eventuale risposta pendente (dopo send_raw)."""
        line, _, self.buf = self._readline()
        self.last = json.loads(line.decode("utf-8"))
        return self.last

    def _readline(self):
        while b"\n" not in self.buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("server chiuso la connessione")
            self.buf += chunk
        line, _, self.buf = self.buf.partition(b"\n")
        return line, b"", self.buf

    def call(self, operation, **fields):
        """Invia {operation, **fields} come JSON-line e legge UNA riga."""
        req = dict(operation=operation, **fields)
        self.sock.sendall((json.dumps(req) + "\n").encode("utf-8"))
        self.last = json.loads(self._readline()[0].decode("utf-8"))
        return self.last

    # -- operazioni del protocollo ------------------------------------------
    def register(self, username, psw):
        return self.call("register", username=username, psw=psw)

    def update_credentials(self, old_u, old_p, new_u=None, new_p=None):
        f = dict(oldUsername=old_u, oldPsw=old_p)
        if new_u is not None:
            f["newUsername"] = new_u
        if new_p is not None:
            f["newPsw"] = new_p
        return self.call("updateCredentials", **f)

    def login(self, username, psw, udp_port=None):
        return self.call("login", username=username, psw=psw,
                         udpPort=udp_port or self.udp_port)

    def logout(self):
        return self.call("logout")

    def submit(self, words):
        return self.call("submitProposal", words=list(words))

    def game_info(self, game_id=-1):
        return self.call("requestGameInfo", gameId=game_id)

    def game_stats(self, game_id=-1):
        return self.call("requestGameStats", gameId=game_id)

    def leaderboard(self, player_name=None, top=None):
        f = {}
        if player_name is not None:
            f["playerName"] = player_name
        if top is not None:
            f["topPlayers"] = top
        return self.call("requestLeaderboard", **f)

    def player_stats(self):
        return self.call("requestPlayerStats")


# ---------------------------------------------------------------------------
# Micro-framework di test (nessuna dipendenza)
# ---------------------------------------------------------------------------

FAILURES = []


def SECTION(name):
    print(f"\n=== {name} ===")


def VERIFY(label, resp, **expect):
    """Verifica una Response: expect puo' avere status / errorCode / payload sub-checks."""
    failures = []
    if "status" in expect and resp.get("status") != expect["status"]:
        failures.append(f"status {resp.get('status')!r} != {expect['status']!r}")
    if "errorCode" in expect and resp.get("errorCode") != expect["errorCode"]:
        failures.append(f"errorCode {resp.get('errorCode')!r} != {expect['errorCode']!r}")
    payload = resp.get("payload")
    for k, v in expect.get("payload", {}).items():
        got = payload.get(k) if isinstance(payload, dict) else None
        if got != v:
            failures.append(f"payload.{k} {got!r} != {v!r}")
    if failures:
        FAILURES.append(label)
        print(f"  FAIL {label}: {resp}")
        for f in failures:
            print(f"      {f}")
        return False
    print(f"  ok  {label}")
    return True


def summary():
    print(f"\n==== FAILURES: {len(FAILURES)} ====")
    for f in FAILURES:
        print("  -", f)
    return len(FAILURES) == 0


# ---------------------------------------------------------------------------
# Avvio server su config temporanea (porte/file in /tmp)
# ---------------------------------------------------------------------------

def _free_port():
    s = socket.socket()
    s.bind((HOST, 0))
    p = s.getsockname()[1]
    s.close()
    return p


def start_test_server(project_dir, game_duration=60, pool_size=16):
    """Scrive una config temp e avvia il server reale. Ritorna (proc, tcp_port, cfgfile)."""
    tcp_port = _free_port()
    udp_port = _free_port()
    tmp = f"/tmp/conn_test_{os.getpid()}"
    os.makedirs(tmp, exist_ok=True)
    cfg = os.path.join(tmp, "server.properties")
    with open(cfg, "w") as f:
        f.write(f"tcp.port={tcp_port}\n"
                f"udp.port={udp_port}\n"
                f"game.duration.sec={game_duration}\n"
                f"pool.size={pool_size}\n"
                f"games.file={project_dir}/data/games.json\n"
                f"persist.file={tmp}/users.json\n"
                f"history.file={tmp}/history.json\n"
                f"persist.interval.sec=300\n")
    cp = f"out:lib/gson-2.11.0.jar"
    proc = subprocess.Popen(
        ["java", "-cp", cp, "server.core.ServerMain", cfg],
        cwd=project_dir,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    # attende che il server ascolti
    deadline = time.time() + 15
    ready = False
    while time.time() < deadline:
        if proc.poll() is not None:
            out = proc.stdout.read().decode() if proc.stdout else ""
            raise RuntimeError(f"server exited early:\n{out}")
        try:
            s = socket.create_connection((HOST, tcp_port), timeout=1)
            s.close()
            ready = True
            break
        except OSError:
            time.sleep(0.2)
    if not ready:
        proc.kill()
        raise RuntimeError("server non si e' avviato in tempo")
    # drena il banner di boot
    time.sleep(0.3)
    return proc, tcp_port, cfg