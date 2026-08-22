# STATUS — Server Connections (Java)

## SERVER (P0 foundation + P1 logic + P2 in corso)
| ID | Componente | Stato | Note |
|----|-----------|-------|------|
| S1 | Config (`core/ServerConfig`) | 🟢 | arg opzionale path; `historyFile` nuovo campo; gestione eccezioni esplicita |
| S2 | Loader partite (`loader/GameLoader`) | 🟢 | **PIGRO**: streaming Gson `JsonReader` O(1); `CyclicGameIterator` (classe pubblica); skip entry malformate a runtime; verificato |
| S3 | UserStore (`core/UserStore`) | 🟢 | **spostato in `core/`** (da `persistence/`); id immutabile + indice `nameToId`; `getById`/`getByName`; per-account lock; psw in chiaro; persist atomico **+ synchronized** (3 fonti: timer/scheduler/shutdown-hook); verificato |
| S4 | Game model (`core/ActiveGame`/`PlayerState`) | 🟢 | `roundId` univoco + `finalized` (AtomicBoolean); stato per **userId**; valutazione malformata/errata; score; outcome; verificato |
| S5 | Scheduler (`network/GameScheduler`) | 🟢 | sleep→finalize→**persist event-driven (utenti+storico)**→UDP(roundId)→rotate(auto-join online) |
| S6 | TCP acceptor+pool (`network/ConnectionAcceptor`) | 🟢 | |
| S7 | Handler (`network/ClientHandler`) | 🟢 | dispatch 9 op; gate `loggedInUserId` (id immutabile); catch(Exception) non uccide il worker; null=OK |
| S8 | UDP notifier (`network/UdpNotifier`) | 🟡 | pronto; ricezione client in P2; payload con `roundId` |
| S9 | Classifica+stats+storico (`core/GameManager`) | 🟢 | leaderboard (cumulative), game/player stats; **storico persistito** (`historyFile`, cap 1000); `onlineUsers` auto-join; verificato |
| S10 | Persistenza (`persistence/PersistenceThread`) | 🟢 | persiste **utenti + storico** (non solo utenti) |
| S11 | Protocollo errori (`protocol/Errors`) | 🟢 | enum centralizzato; `null`=OK; `ERR_ALREADY_LOGGED_IN` **rimosso** |
| S12 | Envelope (`protocol/Request`/`Response`) | 🟢 | `gameId=-1` ⇒ corrente; `requestGameInfo` storico espone `assignment`+tema |

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
