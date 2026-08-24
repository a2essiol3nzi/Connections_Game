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
`synchronized(ctx.games)` attorno a `submitProposal` + `gameInfo` (serializza con
`rotate`). `SubmitResult.isOk()` ⇒ payload `{result, game}`; altrimenti
`Response.err(r.error())` (mappato 1:1 sull'enum `Errors`).

## Collegamenti
- `protocol/Request`, `protocol/Response`: envelope.
- `core/Context`: `users`, `games`, `udpRegistry`.
- `core/GameManager`: operazioni per userId.
- `network/UdpRegistry`: registra/rimuove endpoint.
