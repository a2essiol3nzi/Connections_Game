# `network/ClientHandler` — gestione di UNA connessione client

## Ruolo
`Runnable` su connessione TCP persistente (pool di thread). Protocollo
**line-based** JSON (`\n`). I/O bloccante server; NIO lato client (§3).

## Stato per-connessione
- `loggedInUserId` — **id immutabile** (null se non autenticato); accessibile
  solo dal thread handler ⇒ nessun lock. Alla chiusura →
  `ctx.games.logoutUser(id, ctx.udpRegistry)` (logout implicito atomico: online
  + UDP).

## Flusso (`run`)
Legge righe; `GSON.fromJson` → `Request`; `JsonSyntaxException` ⇒ `BAD_REQUEST` e
prosegue (non chiude la sessione). `dispatch` → `Response` + `\n` + flush.
`IOException` ⇒ disconnessione; `catch(Exception)` ⇒ logga e chiude pulito (NPE
da race non uccide il worker del pool).

**`finally` (disconnessione per QUALSIASI causa)** — client chiuso, ^C/processo
killed, o eccezione: risolve il `username` (o `userId N`) tramite
`ctx.users.getById`, esegue `logoutUser(id, udpRegistry)` e logga
`[Server] client disconnesso: <username> da <IP>`.

## `dispatch` — 9 operazioni
Gate auth centralizzato (`requiresAuth`): submitProposal / requestGameInfo /
requestGameStats / requestLeaderboard / requestPlayerStats richiedono login ⇒
`ERR_NOT_LOGGED_IN`.
- `register` / `updateCredentials` → `UserStore` (null = OK, `Errors` = errore).
- `login`: `udpPort` **obbligatoria** (altrimenti `BAD_REQUEST`);
  `synchronized(ctx.games)` attorno a `getByName` + `registerLogin` (anti-TOCTOU
  tra due handler); se già online ⇒ **`ERR_ALREADY_LOGGED_IN`**; poi registra
  endpoint UDP (IP dal socket TCP + port dal login).
- `logout` → `logoutUser(loggedInUserId, ctx.udpRegistry)`.
- `submitProposal` → `handleProposal`.
- `requestGameInfo` (`roundIdOr(gameId,-1)`), `requestGameStats`,
  `requestLeaderboard` (`null` ⇒ `ERR_PLAYER_NOT_FOUND`), `requestPlayerStats`
  (`null` ⇒ `ERR_USER_NOT_FOUND`).
- `default` ⇒ `UNKNOWN_OPERATION`.

## `handleProposal`
`cases` → `synchronized(ctx.games)` attorno a `submitProposal` + `gameInfo`
(serializza con `rotate`). `SubmitResult.isOk()` ⇒ payload `SubmitPayload`
`{result, game}` (`game` = `GameInfoPayload` dello stato aggiornato);
altrimenti `Response.err(r.error())` (mappato 1:1 sull'enum `Errors`).
Lato client, un `submit` che porta a vittoria/sconfitta (`correct>=3` o
`errors>=4`) triggera anche una `requestGameStats` (vedi `client/Cli.cmdSubmit`).

## Collegamenti
- `protocol/Request`, `protocol/Response`: envelope.
- `protocol/payload/*`: tipi del payload (GameInfo/GameStats/Leaderboard/
  PlayerStats/Submit).
- `core/Context`: `users`, `games`, `udpRegistry`.
- `core/GameManager`: operazioni per userId (ritornano i `*Payload`).
- `network/UdpRegistry`: registra/rimuove endpoint.
