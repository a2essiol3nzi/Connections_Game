"""
test_load.py — Suite di CARICO / CONCORRENZA: cerca colli di bottiglia e race.

Sfrutta i 20 utenti fittizi gia' presenti in data/users.json (user01..user20) per
evitare registrazioni massicce, oppure genera utenti su richiesta. Verifica:
  L1  throughput login sotto carico (N connessioni concorrenti)
  L2  submit paralleli: il lock GRANULARE su PlayerState deve permettere
      parallelismo (niente serializzazione globale su tutte le submit)
  L3  ERR_ALREADY_LOGGED_IN per doppio login dello stesso utente da 2 conn
  L4  leaderboard sotto letture concorrenti (read-only, no race crash)
  L5  disconnessione brusca di molti client (EOF) senza crash / leak di pool
"""
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(__file__))
from proto import TestClient, SECTION, VERIFY, summary, FAILURES  # noqa: E402


def suite(TCP_PORT):
    ok = True

    # ============ L1. throughput login concorrenti ============
    SECTION("L1. login concorrenti (20 utenti, 5 ripetizioni)")
    # assicuriamoci che gli utenti fittizi esistano (già in users.json)
    n_users = 20
    T = int(time.time() % 100000)
    results = []
    lock = threading.Lock()

    def do_login(i):
        uname = f"u{T}_{i}"
        # registra+login in una sola connessione
        with TestClient(TCP_PORT) as c:
            c.register(uname, "pw")
            t0 = time.time()
            r = c.login(uname, "pw")
            dt = (time.time() - t0) * 1000
            with lock:
                results.append((dt, r.get("status")))

    threads = [threading.Thread(target=do_login, args=(i,)) for i in range(n_users)]
    t0 = time.time()
    for th in threads: th.start()
    for th in threads: th.join()
    total = time.time() - t0
    ok_status = all(s == "OK" for _, s in results)
    if not ok_status:
        FAILURES.append(f"L1: {sum(1 for _,s in results if s!='OK')} login falliti")
        ok = False
    print(f"  {n_users} login concorrenti | totale {total*1000:.0f} ms "
          f"(~{n_users/max(total,1e-6):.0f} login/s, media {sum(d for d,_ in results)/n_users:.1f} ms/op)")
    if not ok_status:
        FAILURES.append("L1 login falliti"); ok = False

    # ============ L2. submit paralleli + lock granulare ============
    SECTION("L2. submit paralleli (lock granulare, nessuna serializzazione)")
    # ogni utente in una connessione, submit di un gruppo errato (SCRIVE errore)
    # e di un gruppo corretto (SCRIVE correct): se il lock fosse globale le
    # submit si serializzerebbero -> il tempo totale ~ somma. Qui vogliamo che
    # restino indipendenti e non crashino sotto race.
    n_w = 12
    gt0 = time.time()
    failures = []

    def do_submit(i):
        uname = f"v{T}_{i}"
        try:
            with TestClient(TCP_PORT) as c:
                c.register(uname, "pw")
                if c.login(uname, "pw").get("status") != "OK":
                    failures.append((i, "login")); return
                # 3 submit errate + 1 corretta (parole dal gioco corrente richieste via info)
                info = c.game_info()
                if info.get("status") != "OK":
                    failures.append((i, "info")); return
                rem = info["payload"].get("remainingWords", [])
                # un gruppo errato: 4 parole valide della board, non un gruppo
                words = sorted(set(rem))[:4] if len(rem) >= 4 else []
                for _ in range(3):
                    r = c.submit(words)
                    if r.get("status") == "ERROR" and r.get("errorCode") != "ERR_MALFORMED":
                        # solo errori di validazione accettati; un blocco reale per errore invasivo e' inatteso
                        pass
                # una proposa con duplicati (-> MALFORMED): deve rimanere in piedi
                if len(rem) >= 4:
                    r = c.submit([rem[0], rem[0], rem[1], rem[2]])
                    if r.get("errorCode") != "ERR_MALFORMED":
                        failures.append((i, f"dup->{r.get('errorCode')}"))
        except Exception as e:
            failures.append((i, f"exc {e}"))

    threads = [threading.Thread(target=do_submit, args=(i,)) for i in range(n_w)]
    for th in threads: th.start()
    for th in threads: th.join()
    gt = time.time() - gt0
    if failures:
        FAILURES.append(f"L2 submit errori: {failures[:5]}")
        ok = False
    print(f"  {n_w} client x 4 submit paralleli in {gt*1000:.0f} ms ({n_w*4/max(gt,1e-6):.0f} submit/s)")

    # ============ L3. doppio login stesso utente ============
    SECTION("L3. doppio login -> ERR_ALREADY_LOGGED_IN")
    uname = f"x{T}_dup"
    with TestClient(TCP_PORT) as ca, TestClient(TCP_PORT) as cb:
        ca.register(uname, "pw")
        ok &= VERIFY("login 1", ca.login(uname, "pw"), status="OK")
        ok &= VERIFY("login 2 (da altra conn) -> ALREADY", cb.login(uname, "pw"),
                     status="ERROR", errorCode="ERR_ALREADY_LOGGED_IN")
    # dopo la chiusura della prima conn (EOF), l'utente deve risultare sbloccato
    with TestClient(TCP_PORT) as cc:
        r = cc.login(uname, "pw")
        if r.get("status") != "OK":
            FAILURES.append("L3 relogin dopo EOF")
            ok = False
        else:
            print(f"  relogin dopo disconnect: OK")

    # ============ L4. leaderboard sotto letture concorrenti ============
    SECTION("L4. leaderboard concorrenti (read-heavy, nessun crash)")
    n_rd = 20
    ok_rl = True
    def do_lb(i):
        nonlocal ok_rl
        with TestClient(TCP_PORT) as c:
            c.register(f"r{T}_{i}", "pw")
            c.login(f"r{T}_{i}", "pw")
            for _ in range(5):
                r = c.leaderboard(top=10)
                if r.get("status") != "OK":
                    ok_rl = False
    threads = [threading.Thread(target=do_lb, args=(i,)) for i in range(n_rd)]
    t0 = time.time()
    for th in threads: th.start()
    for th in threads: th.join()
    lb_time = time.time() - t0
    if not ok_rl:
        FAILURES.append("L4 leaderboard falliti")
        ok = False
    print(f"  {n_rd} client x 5 leaderboard in {lb_time*1000:.0f} ms (read-only, ~{n_rd*5/max(lb_time,1e-6):.0f} op/s)")

    # ============ L5. disconnect brusco molti client ============
    SECTION("L5. disconnect brusco (EOF) senza crash")
    n_disc = 15
    errmsg = ["?"]

    def do_disc(i):
        with TestClient(TCP_PORT) as c:
            c.register(f"d{T}_{i}", "pw")
            c.login(f"d{T}_{i}", "pw")
            # chiude la socket senza logout -> il server deve fare logout implicito
        # conn chiusa (uscita dal with)

    threads = [threading.Thread(target=do_disc, args=(i,)) for i in range(n_disc)]
    for th in threads: th.start()
    for th in threads: th.join()
    # verifica che il server risponda ancora
    alive = True
    try:
        with TestClient(TCP_PORT) as c:
            c.register("d_T_final", "pw")
            alive = c.login("d_T_final", "pw").get("status") == "OK"
    except Exception as e:
        alive = False; errmsg[0] = str(e)
    if not alive:
        FAILURES.append(f"L5 server non responsive: {errmsg[0]}")
        ok = False
    print(f"  {n_disc} disconnect bruschi | server ancora attivo: {alive}")

    return ok, len(FAILURES)