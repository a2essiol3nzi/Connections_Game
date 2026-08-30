# `network/ClientHandler` — gestione di una connessione TCP

## Ruolo
Gestisce UNA connessione client persistente sul pool di thread (bloccante, I/O
`readLine` su TCP). Il `run()` legge richieste line-based JSON, le dispatcha e
scrive la `Response`. Stato per-connessione: `loggedInUserId` (null se non
autenticato), accessibile solo dal thread handler.

## Stato interno
- `socket`, `ctx` (`Context` con risorse condivise).
- `loggedInUserId` — `Integer`, null finché non autenticato.

## Flusso
- `run()`: apre `BufferedReader`/`BufferedWriter` UTF-8; loop su `readLine()`.
  JSON illeggibile → `BAD_REQUEST` (senza chiudere la sessione). Ogni `line` →
  `dispatch(req, clientIp)` → scrive `Response` su `out`. `IOException` (EOF/
  reset) e `Exception` inattese non uccidono il worker del pool.

- `finally`: disconnessione per QUALSIASI causa → **logout implicito**:
  `ctx.games.logoutUser(loggedInUserId, ctx.udpRegistry)` (rimuove da onlineUsers
  e deregistra l'endpoint UDP), log del chi.

## `dispatch(req, clientIp)`
Gate auth centralizzato: `submitProposal`/`requestGameInfo`/`requestGameStats`/
`requestLeaderboard`/`requestPlayerStats` richiedono `loggedInUserId != null`
altrimenti `ERR_NOT_LOGGED_IN`. `register`/`login` non possono avvenire da client
già loggato → `ERR_ALREADY_LOGGED_IN`.

Operazioni (switch):
- `register` → `users.register`, `null`=OK.
- `updateCredentials` → `users.updateCredentials`.
- `login` → valida `udpPort` (obbligatorio per notifiche UDP, `BAD_REQUEST` se
  assente); `users.login`; poi **atomicità**: `synchronized(ctx.games)` attorno a
  `getByName` + `registerLogin` (anti-TOCTOU: due handler non loggano lo stesso
  utente). Registra endpoint UDP (`udpRegistry.register`) dall'IP del socket TCP
  e la porta del login; su `UnknownHostException` fa rollback (`registerLogout`)
  e risponde `BAD_REQUEST`. Ritorna `gameInfo(...)` del giocatore.
- `logout` → `games.logoutUser`, azzera `loggedInUserId`.
- `submitProposal` → `handleProposal`.
- `requestGameInfo` → `gameInfo` (roundId da `req.gameId`, default -1); `null` →
  `ERR_NO_ACTIVE_GAME`/`ERR_GAME_NOT_FOUND`.
- `requestGameStats` → `gameStats`; `null` → `ERR_GAME_NOT_FOUND`.
- `requestLeaderboard` → `leaderboard`; `null` → `ERR_PLAYER_NOT_FOUND`.
- `requestPlayerStats` → `playerStats`; `null` → `ERR_USER_NOT_FOUND`.
- default → `UNKNOWN_OPERATION`.

## `handleProposal(words)`
**SENZA lock globali**: niente `synchronized(ctx.games)` (prima era un doppio
lock ridondante attorno a `submitProposal`, che tratteneva il monitor anche
durante la `gameInfo`). Chiama direttamente `games.submitProposal(...)`, mappa
l'esito (`SubmitPayload`) e ritorna la `gameInfo` aggiornata. Il parallelismo
dei submit è garantito dal lock per-`PlayerState` in `ActiveGame.submit`.

## Concorrenza
Nessun campo condiviso tra thread (ogni handler è 1:1 con una connessione). Il
solo lock residuo è `synchronized(ctx.games)` nel ramo `login` (breve, per
atomicità getByName+registerLogin vs rotate). `submitProposal`/`gameInfo`/etc.
sono lock-free lato `GameManager`.

## Collegamenti
- `core/Context`: risorse condivise (`users`, `games`, `udpRegistry`).
- `core/UserStore`, `core/GameManager`, `core/ActiveGame`.
- `protocol/*`: `Request`, `Response`, `Errors`, `payload/*`.
- `network/ConnectionAcceptor`: istanzia il handler sul pool.