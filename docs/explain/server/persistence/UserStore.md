# `persistence/UserStore` — store utenti + persistenza JSON

## Ruolo
Registrazione, login, aggiornamento credenziali, statistiche e persistenza su
file JSON. Cuore della gestione identità del server.

## ID univoco immutabile (consiglio prof.)
Mappa primaria `<id, User>` (id **immutabile**, struttura mai cambiata da
rename); indice secondario `nameToId` (`<username, id>`) è l'unico toccato
alla rinomina. Così le modifiche di credenziali non alterano strutture che
referenziano l'utente per id.

## Campi
- `byId` — `ConcurrentHashMap<Integer,User>` (primaria).
- `nameToId` — `ConcurrentHashMap<String,Integer>` (indice).
- `nextId` — `AtomicInteger` (generatore id monotono).
- Inner `User` — `id` è **`final`** (impostato nel costruttore `User(int userId)`);
  `username`/`password` (in chiaro) mutabili; statistiche
  (`cumulativeScore`, `puzzlesPlayed/Won/Lost`, `notFinished`, `currentStreak`,
  `maxStreak`, `perfectPuzzles`, `mistakeHist[6]`).

## Metodi
- `register` — **barriera atomica**: `nameToId.putIfAbsent(username, -1)`
  rivendica lo username PRIMA di consumare l'id (`nextId.getAndIncrement()`),
  così due thread concorrenti non superano entrambi il check e non si
  sovrascrivono. Ritorna `null` = OK, stringa = codice errore.
- `login` / `updateCredentials` → `null` = OK, stringa = errore; login e
  aggiornamento sotto `synchronized(u)`.
- `allUsers()` / `hasUser()` / `getByName()` — lookup per classifica/stats.
- `persist()` — **`synchronized`**: chiamato da TRE fonti concorrenti, ne
  serializza la scrittura su `file.tmp` + `ATOMIC_MOVE`:
  1. `PersistenceThread` (timer periodico);
  2. `GameScheduler`, subito dopo `finalizeGame` (persistenza event-driven);
  3. shutdown hook in `ServerMain` (SIGTERM/SIGINT) → nessuna perdita
     dell'ultima partita finalizzata all'uscita.
- `load()` (privato, all'avvio) — file assente ⇒ messaggio e partenza vuota;
  file corrotto (qualunque `Exception`) ⇒ rinominato in `*.corrupt` e partenza
  vuota; altrimenti ripristina utenti e `nextId`.

## SEMPLIFICAZIONE PASSWORD
Salvate **in chiaro** (scelta consapevole: il progetto non focalizza la
sicurezza). In produzione → hash+salt. Non copiare in contesti reali.

## Concorrenza
Solo `synchronized`/`Atomic*`/`ConcurrentHashMap` (vincolo NO `locks.*`):
lock **per-account** (`synchronized(u)`) ⇒ due utenti diversi non si
bloccano; `register` rivendica lo username atomicamente prima di allocare l'id.
`persist()` è `synchronized` a livello di store per serializzare i tre writer.

## Collegamenti
- `core/GameManager`: `finalizeGame` aggiorna statistiche `User`.
- `persistence/PersistenceThread`: chiama `persist()` (timer).
- `network/GameScheduler`: chiama `persist()` post-finalize.
- `core/ServerMain`: shutdown hook chiama `persist()`.
