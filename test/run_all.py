#!/usr/bin/env python3
"""
run_all.py — Esegue le suite di test contro un server reale su config temporanea.

Uso:  python3 test/run_all.py [--func] [--load]
Nessun argomento: esegue entrambe le suite. Non tocca data/users.json di
produzione (il server gira su file persist/history in /tmp).
Alla fine riporta i fallimenti e l'exit code.
"""
import os
import sys
import time

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(PROJ, "test"))
os.chdir(PROJ)

from proto import start_test_server, summary  # noqa: E402


def main():
    run_func = "--load" not in sys.argv
    run_load = "--func" not in sys.argv

    print(f"[setup] build: make out/.stamp")
    os.system("make out/.stamp >/dev/null 2>&1")

    print("[setup] avvio server su config temporanea ...")
    proc, tcp_port, cfg = start_test_server(PROJ, game_duration=180, pool_size=16)
    code = 0
    try:
        if run_func:
            import test_func
            f_ok, f_fail = test_func.suite(tcp_port)
            code += (0 if f_ok else 1)
        if run_load:
            import test_load
            l_ok, l_fail = test_load.suite(tcp_port)
            code += (0 if l_ok else 1)
        print("\n===== RISULTATO =====")
    finally:
        print(f"\n[teardown] arresto server (SIGTERM)...")
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        print("[teardown] server fermato")

    ok = summary()
    sys.exit(0 if (ok and code == 0) else 1)


if __name__ == "__main__":
    main()