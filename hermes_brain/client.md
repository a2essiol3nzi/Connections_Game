# CLIENT — Connections (piano)

## Framework (da §3, ordinamento NUOVO)
- Java `javac`; CLI (GUI non valutata → skippata).
- **NIO** obbligatorio sul TCP (`SocketChannel`+`Selector`).
- UDP: receiver di notifiche async `GAME_ENDED` (unicast da server).
- Messaggi JSON line-based `\n`, envelope server già fermo:
  - OK:    `{"status":"OK","payload":{...}}`
  - ERROR: `{"status":"ERROR","errorCode":"ERR_...","message":"...","payload":null}`
- Stessi POJO JSON lato client (`protocol/Request.java` shape, package condiviso).
- Config da `client.properties`, NIENTE CLI/interattivo (§4).

## Thread lato client (per relazione)
- **main/CLI** — legge stdin, builda `Request`, via `ClientConn.send()`.
- **UdpReceiver** — `DatagramSocket` (port effimera, quella mandata nel `login.udpPort`).
- Sync: unico lock = `synchronized(conn)` in `ClientConn.send()` → serializza
  main+fetch-UDP. Nessun pool client.

## Package `src/client/` (5 classi)
| Classe | Ruolo |
|--------|-------|
| `ClientMain` | `main` (vincolo §4 naming). Carica `ClientConfig`, avvia receiver? No: il socket UDP va bindato PRIMA del login (serve port). Order: create UdpReceiver (bind) → conn NIO → CLI loop |
| `ClientConfig` | legge `client.properties` → `host`, `tcpPort`. UDP port auto (effimera) |
| `ClientConn` (NIO) | `SocketChannel`+`Selector`. `send(Request)->Response` **synchronized(conn)**: scrive JSON+`\n`, leggi riga-risposta. Usato da CLI loop + da UdpReceiver |
| `UdpClient` | bind a port effimera su localhost; thread in ascolto. Su `GAME_ENDED{roundId}` → un solo `requestGameInfo(roundId)` via `ClientConn`: lo scheduler ha già ruotato e il round è storico. Se non loggato ignora |
| `Cli` | loop stdin: parse comando → Request → render payload. |

`client.properties`:
```properties
host=localhost
port=12345
```
(UDP port NON in config e non sul server: è l'effimera del socket receiver, mandata a runtime nel login.)

## Payload che il client deve rendere (da GameManager.java)
- **login/requestGameInfo(corrente)** → `{gameId, sourceGameId, remainingSec, correct, errors, score, finished, remainingWords[16-ok]}`. Render board: 16 parole in griglia + errore/score.
- **requestGameInfo(storico)** → `finished:true, assignment:[{theme, words:[4]}]*4, correct, errors, score, outcome`.
- **requestGameStats** → corrente `{participantsTotal, inProgress, finished, won}` | storico `{participantsTotal, finished, won, avgScore}`.
- **requestLeaderboard** → `{leaderboard:[{username,cumulativeScore}], playerRank?}`.
- **requestPlayerStats** → `{puzzlesCompleted, winRate, lossRate, currentStreak, maxStreak, perfectPuzzles, mistakeHistogram[6]}`.
- **submitProposal** → `{result:"CORRECT"|"WRONG"|"WIN"|..., game:{...board aggiornato}}` (`resultLabel()` da ActiveGame: guarda).

## Comandi CLI
```
help | register <u> <psw> | update <u> <oldP> <newU> <newP>
login <u> <psw> | logout | submit <w1> <w2> <w3> <w4>
info [current]        # requestGameInfo (-1|id)
stats <id>           # requestGameStats
leaders | leaders -k N | leaders -name X
me                   # requestPlayerStats
```
Login = invio `login` con `udpPort` = port del receiver (mandato nel JSON, NON nel file).

## Insidie client
1. **NIO mono-legge e framing byte**: il receiver UDP è UN thread separato; `ClientConn.send` è `synchronized` sul canale per non intrecciare righe. La risposta resta in byte fino a `\n`: non convertire ogni singolo `read()` in `String`, perché UTF-8 può essere spezzato. Non usare `Selector` per UDP.
2. **UDP bound prima del login**: bind effimero a bootstrap, terza alla connessione server.
3. **GAME_ENDED è solo un segnale** → il server ruota prima di inviarlo; il relativo `roundId` risolve subito nello storico completo. Segnale ignorato se no ID / no loggato.
4. **Reorder/firewlen**: `reuseAddress` così da poter riscoltare dopo logout; socket UDP non usato dal server dopo logout.
5. **Errors UI**: stampa `errorCode` umano (mappa `Errors` code→text).
6. **Punteggio wrong/malformed**: render distinto (giallo warn per malformata) seguendo §2.2.

## Cose da confermare su `Cli`
- [ ] `gameId=-1` = corrente (default in info/stats) — confermato in protocol.md (roundId monotono ≠ gameId sorgente).
- [ ] `resultLabel()` — leggere `ActiveGame.java` prima di rendere esito.