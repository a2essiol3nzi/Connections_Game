# `core/UserStore` — identità e statistiche utente

## Ruolo
Store degli UTENTI: registrazione, login, aggiornamento credenziali, statistiche
e persistenza su file JSON atomico. Gestito con `id` immutabile: mappa primaria
`<id,User>` + indice secondario `<username,id>`.

## Stato interno
- `byId` — `ConcurrentHashMap<Integer,User>` (primaria, chiave immutabile).
- `nameToId` — `ConcurrentHashMap<String,Integer>` (indice secondario, cambia
  solo alla rinomina).
- `nextId` — `AtomicInteger` (assegnazione id univoci e monotoni).
- `persistFile`.
- `ioLock` — monitor dedicato per il disk I/O.

Record `User`: `id` `final` immutabile, `username`/`password` (IN CHIARO,
scelta consapevole: non focalizza sicurezza), `cumulativeScore`, `puzzlesPlayed/
Won/Lost`, `notFinished`, `currentStreak`/`maxStreak`, `perfectPuzzles`,
`mistakeHist[6]` ([0..3] vinti con 0..3 err, [4] persi, [5] no-time).

## Operazioni
- `register(username, psw)` — `synchronized(this)`: verifica username, assegna
  `nextId`, popola `nameToId`+`byId`. `null`=OK, altrimenti `Errors`.
- `login(username, psw)` — lookup sotto `synchronized(this)`, confronto psw sotto
  `synchronized(u)`. `null`=OK.
- `updateCredentials(oldUser, oldPsw, newUser, newPsw)` — `synchronized(this)` +
  `synchronized(u)`: rinomina solo l'indice secondario (`nameToId`), `byId` e le
  strutture keyed-by-id restano invariate. `null`=OK.
- `allUsers()` — lista snapshot per classifica.
- `hasUser(name)`, `getByName(name)` (atomica sotto `this`), `getById(id)`
  (lookup diretto, l'id non cambia MAI).
- `persist()` — **NON `synchronized`**: build del JSON in-memory sotto `this`
  (cattura `int`/`mistakeHist` sotto `synchronized(u)`, accoppiata col writer in
  `finalizeGame`), **disk I/O su `ioLock`** dedicato. Le tre fonti concorrenti
  (timer/scheduler/shutdown) si serializzano tra loro tramite `ioLock`, ma
  `register`/`login` (che usano `synchronized(this)`) non si bloccano sulla
  scrittura su disco. Scrittura atomica (tmp + `ATOMIC_MOVE`).
- `load()` — all'avvio (single-threaded): ripristina `byId`/`nameToId`/`nextId`;
  su file corrotto rinomina in `.corrupt` e parte vuoto.

## Concorrenza
Nessun lock globale per le operazioni business: `byId`/`nameToId` sono
`ConcurrentHashMap`; ogni accesso ai campi di un account va sotto
`synchronized(u)` → utenti diversi non si bloccano. `register`/`login`/
`updateCredentials`/`getByName` serializzano il SOLO accesso combinato alle due
mappe con `synchronized(this)` (breve). `persist` separa snapshot in-memory
(`this`) dall'I/O (`ioLock`). Solo `synchronized`/`Atomic*`/`ConcurrentHashMap`.

## Persistenza
Snapshot JSON periodico (PersistenceThread) + event-driven (GameScheduler
post-finalize) + shutdown hook (ServerMain). L'I/O è fuori dal monitor `this`.

## Collegamenti
- `protocol/Errors`: codici di ritorno (enum centralizzato, `null`=OK).
- `core/GameManager`: statistiche in `finalizeGame`, storico per userId,
  `leaderboard`/`playerStats`.
- `network/ClientHandler`: `register`/`login`/`updateCredentials`/`getById`.
- `persistence/PersistenceThread` + `core/ServerMain`: persistenza periodica/shutdown.