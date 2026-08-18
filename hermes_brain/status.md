# STATUS — Server Connections (Java)

## SERVER (P0 foundation + P1 logic + P2 in corso)
| ID | Componente | Stato | Note |
|----|-----------|-------|------|
| S1 | Config (`core/ServerConfig`) | 🟢 | arg opzionale path; gestione eccezioni esplicita |
| S2 | Loader partite (`loader/GameLoader`) | 🟢 | **PIGRO**: indicizzazione span via RandomAccessFile + `gameAt(i)` on-demand; `stream()` lazy; verificato |
| S3 | UserStore (`persistence/UserStore`) | 🟢 | **id immutabile** + indice `nameToId`; **per-account lock**; psw in chiaro (doc); persist atomico; verificato |
| S4 | Game model (`core/ActiveGame`/`PlayerState`) | 🟢 | valutazione malformata/errata; score; outcome; verificato |
| S5 | Scheduler (`network/GameScheduler`) | 🟢 | sleep→finalize→UDP→rotate |
| S6 | TCP acceptor+pool (`network/ConnectionAcceptor`) | 🟢 | |
| S7 | Handler (`network/ClientHandler`) | 🟢 | dispatch 9 op; gate loggedIn; return-convention null=OK |
| S8 | UDP notifier (`network/UdpNotifier`) | 🟡 | pronto; ricezione client in P2 |
| S9 | Classifica+stats (`core/GameManager`) | 🟢 | leaderboard (cumulative), game/player stats; verificato |
| S10 | Persistenza (`persistence/PersistenceThread`) | 🟢 | |
| S11 | Protocollo errori (`protocol/Errors`) | 🟢 | enum centralizzato; `null`=OK |
| S12 | Envelope (`protocol/Request`/`Response`) | 🟢 | |

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
