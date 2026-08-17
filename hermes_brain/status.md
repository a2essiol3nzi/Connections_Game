# Board di sviluppo (Second Brain)

Stati: 🔴 non iniziato · 🟡 in corso · 🟢 fatto · ⚪ bloccato

## SERVER
| ID | Componente | Stato | Note / Task |
|----|-----------|-------|-------------|
| S1 | Config loader (`server.properties`) | 🔴 | porte, durata partita, path JSON, periodo persistenza |
| S2 | Loader JSON partite (sorgente docenti) | ⚪ | **FILE MANCANTE** nel repo — richiedere/ottenere; definire schema atteso |
| S3 | User store + persistenza JSON | 🔴 | `Map<username,User>`; hash password; save/load atomico |
| S4 | Game model + logica valutazione | 🔴 | 16 parole, 4 gruppi; eval proposta; distinzione malformata/errata |
| S5 | Scheduler partita attiva | 🔴 | start/timeout/end + avvio successiva; lock su `activeGame` |
| S6 | TCP acceptor + thread pool | 🔴 | `ServerSocket` + `ExecutorService` (fixed) |
| S7 | Handler per-client (dispatch protocollo) | 🔴 | ciclo read JSON → azione → write JSON |
| S8 | Sender notifiche async UDP | 🔴 | fine partita → broadcast UDP ai partecipanti |
| S9 | Classifica + statistiche | 🔴 | leaderboard (metrica da definire), stats partita/giocatore |
| S10 | Thread persistenza periodica | 🔴 | salvataggio utenti+storico a intervalli |

## CLIENT
| ID | Componente | Stato | Note / Task |
|----|-----------|-------|-------------|
| C1 | Config loader (`client.properties`) | 🔴 | host/porta server, porta UDP locale |
| C2 | Connessione TCP + NIO (Selector) | 🔴 | `SocketChannel` non-blocking; send/recv JSON |
| C3 | Receiver notifiche UDP | 🔴 | `DatagramSocket` thread dedicato |
| C4 | CLI / menu comandi | 🔴 | registrazione, login, proposte, query, stats |
| C5 | Stato locale + render info | 🔴 | snapshot partita, stats, disegno tabellare |

## SHARED
| ID | Componente | Stato | Note / Task |
|----|-----------|-------|-------------|
| SH1 | Layer JSON (de)serialization | 🔴 | scelta lib vs hand-roll |
| SH2 | Definizione messaggi + codici errore | 🔴 | vedere `protocol.md` |
| SH3 | Auth (hash password + salt) | 🔴 | mai plaintext |

## BUILD & CONSEGNA
| ID | Componente | Stato | Note / Task |
|----|-----------|-------|-------------|
| B1 | Compilazione `javac` pulita | 🔴 | no file IDE |
| B2 | JAR eseguibili (client+server) | 🔴 | `Main-Class` in manifest |
| B3 | File di config (client/server) | 🔴 | no param interattivi/CLI |
| B4 | PDF relazione ≤5 pag | 🔴 | schema thread, strutture dati, sync, istruzioni |

## Blocchi critici
- ⚪ **S2**: il JSON delle 911 partite non è nel repo. Senza di esso il server
  non ha dati di gioco → blocco runtime. Recuperare dal docente o generare
  dataset di test con lo schema definito in `protocol.md`/README.
