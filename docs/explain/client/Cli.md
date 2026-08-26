# `Cli` — loop CLI + render delle risposte

## Ruolo
Loop di sinistra su stdin: legge un comando, lo traduce in una `Request`
(GSON/`client`), invia via `ClientConn` e renderizza la `Response`. **Stateless
per-call**: lo stato del gioco è sul server; la CLI ridisegna ogni risposta.

## Stato
- `conn` — `ClientConn` (via di trasporto).
- `udpPort` — porta UDP effimera da mettere nel `login`.

## Loop (`run` → `dispatch`)
Legge righe da stdin; ogni comando ha un handler dedicato `cmd*`:
- `help` → `printHelp()`.
- `register <user> <psw>` → `cmdRegister` / `update <oldU> <oldP> [newU|-] [newP|-]` → `cmdUpdate` / `login <user> <psw>` → `cmdLogin` (aggiunge `udpPort`) / `logout` → `cmdLogout`.
- `submit w1 w2 w3 w4` → `cmdSubmit` → `submitProposal`.
- `info [roundId]` → `cmdGameInfo` → `requestGameInfo` (default `-1`).
- `stats [roundId]` → `cmdGameStats` → `requestGameStats`.
- `leaders [-k N|-name X]` → `cmdLeaders` → `requestLeaderboard`.
- `me` → `cmdMe` → `requestPlayerStats`.
- `quit`/`exit` → termina il loop; altrimenti "Comando sconosciuto".

`parseId` rende `-1` per i default; `-` nelle update indica "non cambiare".

## `sendAndRender`
`conn.sendAndRetreive(r)`; se `ERROR` stampa `[errorCode]: message`; altrimenti
`render(op, payload)`. Su `IOException` stampa l'errore di connessione (non
crasha).

## Render (pubblici, condivisi con UDP)
- `renderGameInfo(JsonObject)` (**statico pubblico**, riusato dal UdpClient):
  round/source id, tempo rimanente, `finished`+`outcome`, correct/errors/score,
  `remainingWords` (griglia da raggruppare) o `assignment` (soluzione + `theme`).
- `renderGameStats`, `renderLeaderboard`, `renderPlayerStats` — stampa tabellare.
- `submitProposal` → `>>> result` + render della board aggiornata in `game`.

## Collegamenti
- `client/ClientConn`: trasporto.
- `client/UdpClient`: usa `renderGameInfo` dopo una notifica.