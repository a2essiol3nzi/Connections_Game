# `core/UserStore` — store utenti + persistenza JSON

## Ruolo
Registrazione, login, aggiornamento credenziali, statistiche e persistenza su
file JSON. Cuore della gestione identità del server. **Package `core/`**:
vive nel package condiviso perché `GameManager` e `ClientHandler` lo usano
direttamente.

## ID univoco immutabile (consiglio prof.)
Mappa primaria `<id, User>` (id **immutabile**, struttura mai cambiata da
rename); indice secondario `nameToId` (`<username, id>`) è l'unico toccato
alla rinomina. `getById(int)` risolve per id immutabile (resta valido anche
dopo rinomina in partita); `getByName` è `synchronized` sullo store.

## Campi
- `byId` — `ConcurrentHashMap<Integer,User>` (primaria).
- `nameToId` — `ConcurrentHashMap<String,Integer>` (indice).
- `nextId` — `AtomicInteger` (generatore id monotono).
- Inner `User` — `id` **`final`** (costruttore `User(int)`); `username`/`password`
  (in chiaro) mutabili; statistiche (`cumulativeScore`, `puzzlesPlayed/Won/Lost`,
  `notFinished`, `currentStreak`, `maxStreak`, `perfectPuzzles`, `mistakeHist[6]`).

## Metodi (ritornano `Errors` o `null`=OK)
- `register` — `synchronized(this)`: se `username` presente ⇒ `ERR_USERNAME_TAKEN`;
  altrimenti crea `User` con `nextId.getAndIncrement()`. Campi vuoti ⇒ `ERR_INVALID`.
- `login` — `synchronized(this)` per la risoluzione id, poi `synchronized(u)` per
  il check password (`Objects.equals`). Ritorna `ERR_USER_NOT_FOUND` /
  `ERR_WRONG_PASSWORD` / `null`.
- `updateCredentials` — `synchronized(this)` + `synchronized(u)`; rinomina tramite
  `putIfAbsent`/`remove` sull'indice (byId invariato). Ritorna `Errors` o `null`.
- `getByName` / `getById(id)` — lookup (`getByName` sotto lock store, `getById`
  diretto per id immutabile).
- `allUsers()` / `hasUser()` — per classifica/presenza.
- `persist()` — **`synchronized`** su store + `synchronized(u)` per utente:
  chiamato da TRE fonti (PersistenceThread, GameScheduler post-finalize,
  shutdown hook) → serializza scrittura su `file.tmp` + `ATOMIC_MOVE`.
- `load()` (privato) — file assente (primo avvio) ⇒ **parte vuoto in silenzio**
  (niente log: il file viene creato/popolato al primo persist del
  timer/scheduler); corrotto (catch `Exception`) ⇒ rinominato `.corrupt`,
  partenza vuota.

## SEMPLIFICAZIONE PASSWORD
Salvate **in chiaro** (scelta consapevole). In produzione → hash+salt.

## Concorrenza
Solo `synchronized`/`Atomic*`/`ConcurrentHashMap` (NO `locks.*`): lock per-account
(`synchronized(u)`) + lock sullo store per le operazioni di mappa (`register`,
`login`, `getByName`, `updateCredentials`). `persist()` serializza i 3 writer.

## Collegamenti
- `core/GameManager`: `finalizeGame` aggiorna statistiche `User`; `getById`.
- `persistence/PersistenceThread` + `network/GameScheduler`: chiamano `persist()`.
- `network/ClientHandler`: register/login/updateCredentials; usa `u.id`.
- `core/ServerMain`: shutdown hook chiama `persist()`.
