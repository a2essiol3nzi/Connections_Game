# Test suite — Connections Game (Lab3)

Suite di test **Java** che parla il protocollo TCP **via `client.ClientConn`**
(il client NIO già nel codebase: wire JSON-line + deserializzazione in
`protocol.Response`). Nessuna dipendenza esterna oltre a `lib/gson-2.11.0.jar`
(già usata dal progetto). I payload vengono riconvertiti in `protocol.payload.*`
POJO con lo stesso pattern del client reale (`Gson.fromJson(toJson(payload),Cls)`).

Il server viene avviato su una **config temporanea** (porte alte + file
`persist`/`history` in `/tmp`): i test **non toccano** `data/users.json` di
produzione.

## Esecuzione

```bash
make                            # compila i sorgenti
javac -cp 'out:lib/gson-2.11.0.jar' -d test/out \
    test/T.java test/TC.java test/TestFunc.java test/TestLoad.java test/RunAll.java

java -cp 'out:test/out:lib/gson-2.11.0.jar' test.RunAll             # func + load
java -cp 'out:test/out:lib/gson-2.11.0.jar' test.RunAll --load      # solo carico
java -cp 'out:test/out:lib/gson-2.11.0.jar' test.RunAll --func      # solo funzionale
```

Exit code: `0` = tutto verde, `1` = qualche FAIL riportato in fondo.

## File

| File | Cosa fa |
|------|---------|
| `T.java`      | Micro-framework di assertion (`section`/`ok`/`err`/`cond`/`check`/`summary`), nessuna dipendenza |
| `TC.java`     | `TC` = TestClient su `ClientConn` + wrapper tipizzati per le 9 operazioni + riconversione payload→POJO + `startServer()` (boot server su config temp) |
| `TestFunc.java`| Suite **funzionale**: tutte le 9 operazioni, codici di errore, gate auth, regola MALFORMATA-vs-ERRATA (§2.2), case-insensitivity, confini payload |
| `TestLoad.java` | Suite **carico/concorrenza**: throughput login (L1), submit paralleli / lock granulare (L2), doppio login + relogin dopo EOF (L3), leaderboard sotto letture (L4), disconnect bruschi (L5) |
| `TestStats.java`| Suite **stats** su game reali (win/loss/not-finish): streak, winRate/lossRate, perfectPuzzles, mistakeHistogram |
| `TestLoader.java`| Suite **robustezza GameLoader** (unit): `total()`, ordine ciclico, wrap, e skip di voci con data-binding rotto senza crash |
| `RunAll.java` | Orchestratore: build → avvio dei server temp → esegue le suite → arresta → exit code |

## Cosa cercare / colli di bottiglia

- **Lock granulare** su `PlayerState.submit()` (L2): le submit parallele non
  devono serializzarsi su un lock globale.
- **`synchronized(ctx.games)`** in login/rotate (L1): un lock globale su
  GameManager sotto carico di login concorrenti è un punto di serializzazione.
- **`leaderboard()` read-only** senza lock globale (L4): deve restare fluido
  sotto letture concorrenti senza crash.
- **Pool limitato** (16 thread) + `finally` del `ClientHandler` (L5): i worker
  devono tornare al pool e gli utenti sbloccarsi dopo disconnect brusco.

## Note

- Le suite leggono `data/games.json` (prima partita, source gameId 0) per le
  parole reali con cui forzare proposte corrette/errate deterministiche.
- Gli utenti di test vivono nel `/tmp/conn_test_*` della sessione: non
  inquinano la produzione.