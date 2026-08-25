# STATUS — Server Connections (Java)

## SERVER (P0 foundation + P1 logic + P2 in corso)
| ID | Componente | Stato | Note |
|----|-----------|-------|------|
| S1 | Config (`core/ServerConfig`) | 🟢 | arg opzionale path; `historyFile` nuovo campo; gestione eccezioni esplicita |
| S2 | Loader partite (`loader/GameLoader`) | 🟢 | **PIGRO**: streaming Gson `JsonReader` O(1); `CyclicGameIterator` (classe pubblica); skip entry malformate a runtime |
| S3 | UserStore (`core/UserStore`) | 🟢 | **in `core/`**; id immutabile + indice `nameToId`; metodi ritornano `Errors`; `getById`/`getByName`; per-account lock; psw in chiaro; persist atomico **+ synchronized** (3 fonti) |
| S4 | Game model (`core/ActiveGame`/`PlayerState`) | 🟢 | `roundId` univoco + `finalized` (AtomicBoolean); stato per **userId**; enum `JoinResult`/`SubmitResult` con `Errors`; `Outcome`; verificato |
| S5 | Scheduler (`network/GameScheduler`) | 🟢 | sleep→finalize(idempotente)→**persist event-driven (utenti+storico)**→UDP(segnale, non risultati)→rotate(auto-join online); `sleep()` ricalcola deadline e ritenta su interrupt |
| S6 | TCP acceptor+pool (`network/ConnectionAcceptor`) | 🟢 | |
| S7 | Handler (`network/ClientHandler`) | 🟢 | dispatch 9 op; gate auth; `udpPort` obbligatorio + registro UDP; catch(Exception) non uccide worker; `Errors`/`null` |
| S8 | UDP registry+notifier (`network/UdpRegistry`/`UdpNotifier`) | 🟢 | registry userId→endpoint; notifier unicast con **un solo DatagramSocket riusato** (+`connect(addr,port)` per destinatario, non bloccante su host irraggiungibili); payload `GAME_ENDED`+roundId (solo segnale) |
| S9 | Classifica+stats+storico (`core/GameManager`) | 🟢 | leaderboard (cumulative), game/player stats; **storico persistito** (`historyFile`, cap 10_000); `onlineUsers` auto-join; `submitProposal` **synchronized** (anti-TOCTOU); `logoutUser` atomico; `finalizeGame` **idempotente** (`compareAndSet`); verificato |
| S10 | Persistenza (`persistence/PersistenceThread`) | 🟢 | persiste **utenti + storico** |
| S11 | Protocollo errori (`protocol/Errors`) | 🟢 | enum centralizzato; `null`=OK; +`BAD_REQUEST`,`ERR_PLAYER_NOT_FOUND`,`ERR_ALREADY_LOGGED_IN` (login duplicato) |
| S12 | Envelope (`protocol/Request`/`Response`) | 🟢 | `gameId=-1`⇒corrente; `requestGameInfo` storico espone `assignment`+tema; `Response.err(Errors)` con `message` |

## CLIENT
| ID | Componente | Stato | Note |
|----|-----------|-------|------|
| C1 | Client NIO (SocketChannel+Selector) | 🔴 | da fare (P2) |
| C2 | Client UDP receiver | 🔴 | da fare (P2) |
| C3 | Client CLI + render | 🔴 | da fare (P2) |
| C4 | Client State holder | 🔴 | da fare (P2) |

## CONSEGNA (§4)
- [ ] JAR server + JAR client (Main in `core.ServerMain` / `client.ClientMain`)
- [ ] `server.properties` + `client.properties`
- [ ] PDF relazione ≤5 pag (schema thread, strutture dati, sync, istruzioni)
- [ ] allegare `lib/gson-2.11.0.jar`

## VERIFICHE (ad-hoc, /tmp, rimosse)
- P0 envelope OK; P1 logic 27/27; P1 live roundtrip OK.
- Restruct: 22/22 (lazy loader, id store, rotation, socket live OK).
