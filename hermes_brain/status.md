# STATUS — Server Connections (Java)

## SERVER (P0 foundation + P1 logic + P2 in corso)
| ID | Componente | Stato | Note |
|----|-----------|-------|------|
| S1 | Config (`core/ServerConfig`) | 🟢 | arg opzionale path; `historyFile` nuovo campo; gestione eccezioni esplicita |
| S2 | Loader partite (`loader/GameLoader`) | 🟢 | **PIGRO**: streaming Gson `JsonReader` O(1); `CyclicGameIterator` (classe pubblica); skip entry malformate a runtime |
| S3 | UserStore (`core/UserStore`) | 🟢 | **in `core/`**; id immutabile + indice `nameToId`; metodi ritornano `Errors`; `getById`/`getByName`; per-account lock; psw in chiaro; persist atomico: build in-memory sotto `this`, **disk I/O su `ioLock`** (non blocca register/login) |
| S4 | Game model (`core/ActiveGame`/`PlayerState`) | 🟢 | `roundId` univoco + `finalized` (AtomicBoolean); stato per **userId**; enum `JoinResult`/`SubmitResult` con `Errors`; `Outcome`; verificato |
| S5 | Scheduler (`network/GameScheduler`) | 🟡 sperimentale | `ScheduledExecutorService` a un worker: primo task a `endTimeMs`, poi `scheduleWithFixedDelay(duration)`. finalize(idempotente)→**persist event-driven (utenti+storico)**→rotate(auto-join online)→UDP ai partecipanti catturati; il round notificato è già storico e non richiede retry. Il lavoro post-rotate allunga il round osservato. |
| S6 | TCP acceptor+pool (`network/ConnectionAcceptor`) | 🟢 | |
| S7 | Handler (`network/ClientHandler`) | 🟢 | dispatch 9 op; gate auth; `udpPort` obbligatorio + registro UDP; catch(Exception) non uccide worker; `Errors`/`null`; **`handleProposal` senza lock globale** (era double-lock ridondante) |
| S8 | UDP registry+notifier (`network/UdpRegistry`/`UdpNotifier`) | 🟢 | registry userId→endpoint; notifier unicast con **payload e un solo DatagramPacket riusati**: per endpoint aggiorna solo la destinazione, senza `connect` e senza garanzia di consegna; errore di invio per destinatario loggato con `userId`, poi continua con gli endpoint successivi; payload `GAME_ENDED`+roundId (solo segnale) |
| S9 | Classifica+stats+storico (`core/GameManager`) | 🟢 | leaderboard (cumulative), game/player stats; **storico persistito** (`historyFile`, **senza limite/trim** — cresce indefinitamente, richiesta progetto); `onlineUsers` auto-join; **`current` `volatile` + `current()` lock-free**; **`submitProposal` NON più `synchronized`** (parallelismo via lock per-`PlayerState`; TOCTOU gestita da `finalized` CAS); `finalizeGame` rilegge `ps` sotto `synchronized(ps)`; `logoutUser` atomico; `finalizeGame` **idempotente**; **`persistHistory` streamed su `ioLock`**; verificato |
| S10 | Persistenza | 🟢 | **event-driven, NO timer**: `PersistenceThread` rimosso; deltas account → `persistQuiet()` in `register`/`updateCredentials` (perdita crash=0); stats/storico → scheduler post-finalize; shutdown hook all'uscita; `ioLock` su `UserStore`/`GameManager` |
| S11 | Protocollo errori (`protocol/Errors`) | 🟢 | enum centralizzato; `null`=OK; +`BAD_REQUEST`,`ERR_PLAYER_NOT_FOUND`,`ERR_ALREADY_LOGGED_IN` (login duplicato), `ERR_INVALID_CREDENTIALS` (login anti-enumerazione) |
| S12 | Envelope (`protocol/Request`/`Response`) | 🟢 | `roundId=-1`⇒corrente; `requestGameInfo` storico espone `assignment`+tema; `Response.err(Errors)` con `message`. **Payload ora POJO** in `protocol/payload/` (per-op). `Request.roundId` (ex `gameId`) |

## CLIENT
| ID | Componente | Stato | Note |
|----|-----------|-------|------|
| C1 | Client NIO (`client.ClientConn`) | 🟢 | `SocketChannel`+`Selector`; `send()` **synchronized** serializza righe |
| C2 | Client UDP (`client.UdpClient`) | 🟢 | bind effimera (porta nel login); `GAME_ENDED`→singolo fetch TCP dello storico già stabile |
| C3 | Client CLI (`client.Cli`+`ClientMain`) | 🟢 | 9 op + render; board/risultati/stats; payload riconvertiti in **POJO** (`protocol/payload`) via `Cli.payload(res,Class)` |
| C4 | Client config (`client.ClientConfig`+`client.properties`) | 🟢 | `host`+`port`; UDP effimera |

> **VERIFICA live riuscita**: register/login/info/me/leaders/stats/logout; submit CORRECT/WRONG
> + penalità, MALFORMATO senza penalty; win→18pt finished; **UDP `GAME_ENDED` → auto-fetch storico**
> (temi+esito) con retry. Reusa `protocol.Request`/`Response` (package condiviso) e `protocol.GameEnded` (POJO UDP).

## CONSEGNA (§4)
- [ ] JAR server + JAR client (Main in `core.ServerMain` / `client.ClientMain`)
- [ ] `server.properties` + `client.properties`
- [ ] PDF relazione ≤5 pag (schema thread, strutture dati, sync, istruzioni)
- [ ] allegare `lib/gson-2.11.0.jar`

## VERIFICHE (ad-hoc, /tmp, rimosse)
- P0 envelope OK; P1 logic 27/27; P1 live roundtrip OK.
- Restruct: 22/22 (lazy loader, id store, rotation, socket live OK).
