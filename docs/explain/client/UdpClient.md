# `UdpClient` — receiver notifiche async UDP

## Ruolo
Riceve le notifiche async `GAME_ENDED` (fine partita) inviate dal server in
**unicast**. Binding a **porta effimera** (`DatagramSocket(0)`): il valore va
mandato al server nel `login` come `udpPort`.

> `GAME_ENDED` è **solo un segnale** (gameId/roundId), NON contiene i risultati.
> Al suo arrivo si va a leggere l'esito via TCP con `requestGameInfo(roundId)`.
> Lo scheduler ha già ruotato `current`, dunque il round notificato viene sempre
> risolto dallo storico completo: non esiste retry lato client.

## Stato
- `sock` — `DatagramSocket` (0 = effimera); `port()` rende il valore da
  mandare nel login.
- `conn` — `ClientConn` per la lettura dell'esito via TCP.
- `running` — `volatile` flag di stop.

## Flusso (`run`)
`while(running)`: `sock.receive` → parse JSON in **`protocol.GameEnded`** → se
`type=="GAME_ENDED"` → `printResult(roundId)`. Payload ignoto/UDP ignorato
(catch Exception). Su `IOException` di receive, se `running` logga e continua.

## `printResult(roundId)` — fetch storico singolo
- Prima dello stampo asincrono chiama `Cli.clearInputLine()` (cancella la riga di
  input corrente, ANSI `\r\u001b[2K`) per non lasciare il prompt sporco/residui.
- Invia `requestGameInfo` (roundId) via `ClientConn.sendAndRetreive`.
- OK ⇒ stampa esito con `Cli.renderGameInfo(GameInfoPayload)` (il payload
  `Object` viene riconvertito con `GSON.fromJson(GSON.toJson(r.payload), GameInfoPayload.class)`); poi ridisegna il prompt `Cli.printPrompt()`.
- `ERR_...` ⇒ esito non disponibile; `IOException` ⇒ abbandona.

`ponytail:` la cancellazione è ANSI, il buffer canonico di stdin non è svuotabile
senza una libreria raw (es. JLine) — gestiamo la parte visiva, il testo già
battuto dall'utente resta nel buffer del terminale. Add JLine quando serva un
prompt raw-mode completo.

## Collegamenti
- `client/ClientConn`: `sendAndRetreive()` per il fetch dell'esito (lock condiviso).
- `client/Cli`: `renderGameInfo` per la stampa; riceve `udpPort` per il login.
- `protocol/GameEnded`: POJO per deserializzare il segnale.
- `protocol/payload/GameInfoPayload`: tipo del payload dell'esito.
- `server/network/GameScheduler`/`UdpNotifier`: fonte del segnale.