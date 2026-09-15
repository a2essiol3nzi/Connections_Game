# `RunAll` — orchestratore delle suite di test

## Ruolo
Runner che: compila (`make`), avvia il server su **config temporanea**, esegue
le suite funzionale e di carico, arresta il server. Exit `0` se tutto verde,
`1` se qualche FAIL.

## Flusso (`main`)
1. `build`: `ProcessBuilder("make")` nella root del progetto; exit `2` se build
   fallita.
2. Avvia il server temp: `TC.startServer(proj, 180)` (durata partita 180s).
3. `runFunc` (asser salvo `--load`) → `TestFunc.run(tcpPort)`.
4. `runLoad` (assen salvo `--func`) → `TestLoad.run(tcpPort)`.
5. `finally`: `srv.stop()` (arresto server).
6. `TestClientConn.run()` — server TCP locale che divide il carattere UTF-8
   `€` fra due invii, per verificare il framing del client.
7. `TestStats.run(proj)` — server **dedicato con durata breve** (rotazione
   rapida) per testare stat a fine round.
8. `TestLoader.run(proj)` — robustezza `GameLoader` (unit, nessun server).
9. Exit: `code == 0 ? 0 : 1`.

## Args
- `--load` ⇒ salta funzionale (solo carico).
- `--func` ⇒ salta carico (solo funzionale).

## Collegamenti
- `TC.startServer`: boot del server temp.
- `TestFunc`, `TestLoad`, `TestClientConn`, `TestStats`, `TestLoader`: suite eseguite.