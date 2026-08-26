# `UdpClient` — receiver notifiche async UDP

## Ruolo
Riceve le notifiche async `GAME_ENDED` (fine partita) inviate dal server in
**unicast**. Binding a **porta effimera** (`DatagramSocket(0)`): il valore va
mandato al server nel `login` come `udpPort`.

> `GAME_ENDED` è **solo un segnale** (gameId/roundId), NON contiene i risultati.
> Al suo arrivo si va a leggere l'esito via TCP con `requestGameInfo(roundId)`.
> Se la notifica arriva prima che il server abbia finalizzato (finestra
> TOCTOU, vedi `hermes_brain/gotchas.md`) la lettura restituisce
> `ERR_GAME_NOT_FOUND`: si riprova per un numero finito di volte poi si abbandona.

## Stato
- `sock` — `DatagramSocket` (0 = effimera); `port()` rende il valore da
  mandare nel login.
- `conn` — `ClientConn` per la lettura dell'esito via TCP.
- `running` — `volatile` flag di stop.

## Flusso (`run`)
`while(running)`: `sock.receive` → parse JSON in **`protocol.GameEnded`** → se
`type=="GAME_ENDED"` → `printResult(roundId)`. Payload ignoto/UDP ignorato
(catch Exception). Su `IOException` di receive, se `running` logga e continua.

## `printResult(roundId)` — retry anti-TOCTOU
- Invia `requestGameInfo` (roundId) via `ClientConn.sendAndRetreive`.
- `ERR_GAME_NOT_FOUND` ⇒ storico non ancora finalizzato ⇒ `sleep(RETRY_DELAY_MS)`
  e riprova (fino a `MAX_RETRY`).
- OK ⇒ stampa esito con `Cli.renderGameInfo(r.payload)`.
- `ERR_...` ≠ not-found ⇒ esito non disponibile; `IOException` ⇒ abbandona.

`MAX_RETRY=10`, `RETRY_DELAY_MS=200` (~2s coprono la finestra TOCTOU).

## Collegamenti
- `client/ClientConn`: `sendAndRetreive()` per il fetch dell'esito (lock condiviso).
- `client/Cli`: `renderGameInfo` per la stampa; riceve `udpPort` per il login.
- `protocol/GameEnded`: POJO per deserializzare il segnale.
- `server/network/GameScheduler`/`UdpNotifier`: fonte del segnale.