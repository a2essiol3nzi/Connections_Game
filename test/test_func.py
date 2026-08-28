"""
test_func.py — Suite FUNZIONALE: copre tutte le 9 operazioni del protocollo,
i codici di errore, la regola MALFORMATA-vs-ERRATA (§2.2), il gate di auth,
la case-insensitivity e i confini dei payload.

Si collega a un server già avviato da run_all.py e usa la prima partita
caricata (gameId 0) note le parole reali dal file games.json.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from proto import TestClient, SECTION, VERIFY, summary, FAILURES, HOST  # noqa: E402

# Parole reali della prima partita (gameId 0), lette dal file definitivo.
_HERE = os.path.dirname(os.path.abspath(__file__))
GAMES = json.load(open(os.path.join(_HERE, "..", "data", "games.json")))
FIRST = GAMES[0]
WORDS_GROUPS = [g["words"] for g in FIRST["groups"]]     # 4 gruppi
ALL_WORDS = [w for grp in WORDS_GROUPS for w in grp]     # 16 parole

# -- parole utili -----------------------------------------------------------
G0 = WORDS_GROUPS[0]              # gruppo corretto completo
G1 = WORDS_GROUPS[1]
WRONG = [WORDS_GROUPS[0][0], WORDS_GROUPS[1][0],
         WORDS_GROUPS[2][0], WORDS_GROUPS[3][0]]          # una da ogni gruppo -> ERRATA
BAD_WORD = ["###NOT-A-WORD###", "SNOW", "HAIL", "RAIN"]   # include parola fuori board -> MALFORMATA
DUP = ["SNOW", "SNOW", "HAIL", "RAIN"]                    # duplicati -> MALFORMATA
MIXED = ["RAIN", "SNOW", "HEAT", "HOCKEY"]                # parola gia' assegnata + parole nuove


def suite(TCP_PORT):
    ok = True
    # ============ 1. REGISTER ============
    SECTION("1. register")
    with TestClient(TCP_PORT) as c:
        ok &= VERIFY("register nuovo utente", c.register("t_user", "pw"), status="OK")
        ok &= VERIFY("register stesso di nuovo", c.register("t_user", "pw"),
                     status="ERROR", errorCode="ERR_USERNAME_TAKEN")
        ok &= VERIFY("register senza psw", c.call("register", username="t_user2"),
                     status="ERROR")  # ERR_INVALID o BAD_REQUEST accettati (campo null)
        # register mentre gia' loggato
        ok &= VERIFY("login per il check register-logged", c.login("t_user", "pw"), status="OK")
        ok &= VERIFY("register da loggato", c.register("t_x", "y"),
                     status="ERROR", errorCode="ERR_ALREADY_LOGGED_IN")
        ok &= VERIFY("logout", c.logout(), status="OK")

    # ============ 2. AUTENTICAZIONE / LOGIN ============
    SECTION("2. login / logout")
    # login senza udpPort
    with TestClient(TCP_PORT) as c:
        ok &= VERIFY("login senza udpPort", c.call("login", username="t_user", psw="pw"),
                     status="ERROR", errorCode="BAD_REQUEST")
        ok &= VERIFY("login con psw errata", c.login("t_user", "sbagliata"),
                     status="ERROR", errorCode="ERR_WRONG_PASSWORD")
        ok &= VERIFY("login utente inesistente", c.login("fantasma", "pw"),
                     status="ERROR", errorCode="ERR_USER_NOT_FOUND")
        ok &= VERIFY("login OK", c.login("t_user", "pw"), status="OK")
        # login doppio sulla STESSA connessione
        ok &= VERIFY("login doppio stessa conn", c.login("t_user", "pw"),
                     status="ERROR", errorCode="ERR_ALREADY_LOGGED_IN")
        # logout senza essere loggato (nuova conn)
        with TestClient(TCP_PORT) as c2:
            ok &= VERIFY("logout non loggato", c2.logout(),
                         status="ERROR", errorCode="ERR_NOT_LOGGED_IN")

    # ============ 3. GATE di AUTH ============
    SECTION("3. operazioni protette senza login -> ERR_NOT_LOGGED_IN")
    with TestClient(TCP_PORT) as c:
        for op, kw in [("submitProposal", {"words": list(G0)}),
                       ("requestGameInfo", {"gameId": -1}),
                       ("requestGameStats", {"gameId": -1}),
                       ("requestLeaderboard", {}),
                       ("requestPlayerStats", {})]:
            ok &= VERIFY(f"{op} senza login", c.call(op, **kw),
                         status="ERROR", errorCode="ERR_NOT_LOGGED_IN")

    # ============ 4. SUBMIT: regola MALFORMATA vs ERRATA (§2.2) ============
    SECTION("4. submitProposal (MALFORMATA vs ERRATA)")
    with TestClient(TCP_PORT) as c:
        ok &= VERIFY("login", c.login("t_user", "pw"), status="OK")
        # 4.0 stato iniziale info
        info = c.game_info()
        ok &= VERIFY("gameInfo live: giocatore senza parole trovate", info,
                     status="OK", payload={"score": 0, "errors": 0, "finished": False})
        # 4.1 errata (una parola per gruppo): SCRIVE (errore, -4)
        ok &= VERIFY("gruppo errato -> WRONG", c.submit(WRONG),
                     status="OK", payload={"result": "WRONG"})
        info = c.game_info()
        ok &= VERIFY("dopo errata: errori=1 score=-4", info,
                     status="OK", payload={"errors": 1, "score": -4})
        # 4.2 malformata con parola fuori board: NON cambia stato
        ok &= VERIFY("parola fuori board -> MALFORMED", c.submit(BAD_WORD),
                     status="ERROR", errorCode="ERR_MALFORMED")
        info = c.game_info()
        ok &= VERIFY("dopo malformata: invariato (errori=1 score=-4)",
                     info, status="OK", payload={"errors": 1, "score": -4})
        # 4.3 malformata per duplicati
        ok &= VERIFY("proposta con duplicati -> MALFORMED", c.submit(DUP),
                     status="ERROR", errorCode="ERR_MALFORMED")
        # 4.4 malformata per parola gia' assegnata a un gruppo trovato
        #     (prima trova il gruppo 0 corretto)
        ok &= VERIFY("gruppo 0 corretto -> CORRECT", c.submit(G0),
                     status="OK", payload={"result": "CORRECT"})
        info = c.game_info()
        # score = 6*correct - 4*errors = 6*1 - 4*1 = 2 (c'era 1 errore precedente)
        ok &= VERIFY("dopo corretto: correct=1 score=2", info,
                     status="OK", payload={"correct": 1, "score": 2})
        # malformata mista: include una parola GIÀ ASSEGNATA + nuove
        ok &= VERIFY("parole miste (gia' assegnata+nuove) -> MALFORMED", c.submit(MIXED),
                     status="ERROR", errorCode="ERR_MALFORMED")
        info = c.game_info()
        ok &= VERIFY("dopo mista malformata: invariato (correct=1 score=2)",
                     info, status="OK", payload={"correct": 1, "score": 2})
        # 4.5 case-insensitivity: gruppo corretto con minuscole
        low = [w.lower() for w in WORDS_GROUPS[1]]
        ok &= VERIFY("gruppo corretto minuscole -> CORRECT (case-insensitive)", c.submit(low),
                     status="OK", payload={"result": "CORRECT"})
        # 4.6 vincita: completa 3 gruppi
        ok &= VERIFY("gruppo 2 corretto -> CORRECT (vittoria)", c.submit(WORDS_GROUPS[2]),
                     status="OK", payload={"result": "CORRECT"})
        info = c.game_info()
        ok &= VERIFY("dopo vittoria: correct=3 finished=true", info,
                     status="OK", payload={"correct": 3, "finished": True})
        # 4.7 submit dopo la fine partita personale -> GAME_OVER_FOR_YOU
        ok &= VERIFY("submit dopo vittoria -> GAME_OVER_FOR_YOU", c.submit(WORDS_GROUPS[3]),
                     status="ERROR", errorCode="ERR_GAME_OVER_FOR_YOU")

    # ============ 5. INFO / STATS ============
    SECTION("5. requestGameInfo / requestGameStats")
    with TestClient(TCP_PORT) as c:
        ok &= VERIFY("login", c.login("t_user", "pw"), status="OK")
        info = c.game_info()
        ok &= VERIFY("gameInfo: 16 parole restanti", info,
                     status="OK", payload={"correct": 3})
        n_rem = len(info["payload"].get("remainingWords", []))
        # dopo 3 gruppi trovati (in sez.4) restano 4 parole (l'ultimo gruppo)
        ok &= VERIFY("remainingWords 4 dopo 3 gruppi trovati", info,
                     status="OK", payload={"correct": 3})
        if n_rem != 4:
            FAILURES.append("remainingWords count"); print(f"  FAIL remainingWords={n_rem} atteso 4")
            ok = False
        ok &= VERIFY("gameStats live OK", c.game_stats(), status="OK")
        # storico round corrente non ancora chiuso -> gameInfo(0) e' lo storico se nel history
        # (roundId della partita corrente parte da 1, non 0)

    # ============ 6. LEADERBOARD ============
    SECTION("6. requestLeaderboard")
    with TestClient(TCP_PORT) as c:
        ok &= VERIFY("login", c.login("t_user", "pw"), status="OK")
        lb = c.leaderboard(top=2)
        ok &= VERIFY("leaderboard OK", lb, status="OK")
        if lb.get("payload") and isinstance(lb["payload"], dict):
            rows = lb["payload"].get("leaderboard", [])
            if len(rows) > 2:
                FAILURES.append("topPlayers cap")
                print(f"  FAIL topPlayers=2 ma {len(rows)} righe")
                ok = False
        # leaderboard per playerName inesistente
        ok &= VERIFY("leaderboard playerName inesistente",
                     c.leaderboard(player_name="nope"),
                     status="ERROR", errorCode="ERR_PLAYER_NOT_FOUND")

    # ============ 7. PLAYER STATS ============
    SECTION("7. requestPlayerStats")
    with TestClient(TCP_PORT) as c:
        ok &= VERIFY("login", c.login("t_user", "pw"), status="OK")
        ps = c.player_stats()
        ok &= VERIFY("playerStats OK", ps, status="OK")
        if ps.get("payload"):
            p = ps["payload"]
            if p.get("puzzlesCompleted", -1) < 0:
                FAILURES.append("puzzlesCompleted"); print("  FAIL puzzlesCompleted mancante"); ok = False
            if not isinstance(p.get("mistakeHistogram"), list):
                FAILURES.append("histogram type"); print("  FAIL histogram non lista"); ok = False
            elif len(p.get("mistakeHistogram", [])) != 6:
                FAILURES.append("histogram len"); print("  FAIL histogram != 6 bucket"); ok = False

    # ============ 8. UPDATE CREDENTIALS ============
    SECTION("8. updateCredentials")
    # la rinomina NON sloga l'utente (sessione persistente): per provarla al
    # login si deve usare una NUOVA connessione per ogni step.
    with TestClient(TCP_PORT) as c:
        ok &= VERIFY("login t_ucred", c.login("t_user", "pw"), status="OK")
        ok &= VERIFY("rinomina username", c.update_credentials("t_user", "pw", new_u="t_user2"),
                     status="OK")
        # la stessa sessione resta loggata con il nuovo nome
        info = c.game_info()
        if info.get("status") != "OK":
            FAILURES.append("sessione morta dopo rinomina"); ok = False
        ok &= VERIFY("logout", c.logout(), status="OK")
    # nuova connessione: login col nuovo nome
    with TestClient(TCP_PORT) as c:
        ok &= VERIFY("login col nuovo nome", c.login("t_user2", "pw"), status="OK")
    # nuova connessione: cambio psw
    with TestClient(TCP_PORT) as c2:
        ok &= VERIFY("login per cambio psw", c2.login("t_user2", "pw"), status="OK")
        ok &= VERIFY("cambio psw", c2.update_credentials("t_user2", "pw", new_p="pw2"),
                     status="OK")
        ok &= VERIFY("logout", c2.logout(), status="OK")
    with TestClient(TCP_PORT) as c3:
        ok &= VERIFY("login nuova psw", c3.login("t_user2", "pw2"), status="OK")
        ok &= VERIFY("logout", c3.logout(), status="OK")
    with TestClient(TCP_PORT) as c4:
        ok &= VERIFY("login vecchia psw ora errata", c4.login("t_user2", "pw"),
                     status="ERROR", errorCode="ERR_WRONG_PASSWORD")

    # ============ 9. INPUT HOSTILI ============
    SECTION("9. input malformati / operazioni sconosciute")
    with TestClient(TCP_PORT) as c:
        # JSON illeggibile
        c.send_raw(b"{not json\n")
        r = c.read()
        ok &= VERIFY("json malformato -> BAD_REQUEST", r,
                     status="ERROR", errorCode="BAD_REQUEST")
        # operation vuota -> UNKNOWN_OPERATION (stringa vuota ma presente -> default)
        ok &= VERIFY("operation vuota -> UNKNOWN_OPERATION", c.call(""),
                     status="ERROR", errorCode="UNKNOWN_OPERATION")
        # operation mancante del tutto -> BAD_REQUEST (null)
        ok &= VERIFY("operation mancante (null) -> BAD_REQUEST",
                     c.call(None), status="ERROR", errorCode="BAD_REQUEST")
        # operazione sconosciuta
        ok &= VERIFY("operation sconosciuta", c.call("doEvilThings"),
                     status="ERROR", errorCode="UNKNOWN_OPERATION")

    return ok, len(FAILURES)