# Test suite — Connections Game (Lab3)

Suite di test in **Python stdlib** (nessuna dipendenza) che parla il
**protocollo TCP grezzo** (`JSON` + `\n`) direttamente contro il server Java.
Non usa il client CLI: così è facile automatizzare, parallelizzare e misurare
il carico.

Il server viene avviato su una **config temporanea** (porte alte + file
`persist`/`history` in `/tmp`): i test **non toccano** `data/users.json` di
produzione.

## Esecuzione

```bash
make            # compila i sorgenti
python3 test/run_all.py            # suite funzionale + carico
python3 test/run_all.py --load     # solo carico/concorrenza
python3 test/run_all.py --func     # solo funzionale
```

Exit code: `0` = tutto verde, `1` = qualche FAIL riportato in fondo.

## File

| File | Cosa fa |
|------|---------|
| `proto.py`        | Driver raw (TestClient) + `start_test_server()` (boot server su config temp) + micro-framework `VERIFY`/`SECTION`/`summary` |
| `test_func.py`    | Suite **funzionale**: tutte le 9 operazioni, codici di errore, gate di auth, regola MALFORMATA-vs-ERRATA (§2.2), case-insensitivity, confini payload |
| `test_load.py`    | Suite **carico/concorrenza**: throughput login, submit paralleli (lock granulare), doppio login, leaderboard sotto letture, disconnect bruschi |
| `run_all.py`      | Orchestratore: compila, avvia server temp, esegue le suite, arresta server, riporta exit code |

## Cosa cercare / colli di bottiglia

- **Lock granulare** su `PlayerState.submit()`: il test L2 misura se le submit
  parallele si serializzano o restano indipendenti (throughput submit/s).
- **`synchronized(ctx.games)`** in `login`/`registerLogin`/`rotate`: un lock
  globale su GameManager. Sotto carico di login concorrenti (L1) si vede se
  resta un punto di serializzazione.
- **`leaderboard()`** read-only senza lock globale (L4): deve restare fluido
  sotto letture concorrenti senza crash.
- **Pool limitato** (16 thread): con N connessioni bloccate in attesa, il pool
  può esaurirsi (L5 con disconnect bruschi verifica che i worker vengano
  liberati dal `finally` del `ClientHandler`).
- **Disconnessione senza logout**: il `finally` deve fare logout implicito e
  sbloccare l'utente (L3/L5).

## Note

- La prima partita caricata (source gameId `0`) è deterministica: le suite la
  usano con le parole reali lette da `data/games.json` (registrazione + login
  nel primo round della partita attiva).
- Gli utenti di test vengono creati e lasciati nel `/tmp/.../users.json` della
  sessione: non inquinano la produzione.