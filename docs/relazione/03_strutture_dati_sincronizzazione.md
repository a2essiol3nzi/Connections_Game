# Strutture dati e primitive di sincronizzazione

La maggior parte della sincronizzazione avviene su lato server (usa un grado di parallelismo più alto).

## Lato server

### Mappe concorrenti

La base di gran parte dello stato condiviso lato server è `ConcurrentHashMap`.

- `UserStore`: `byId` (`ConcurrentHashMap<Integer, User>`) è la mappa **primaria**, con chiave l'**id immutabile** dell'utente; `nameToId` (`ConcurrentHashMap<String, Integer>`) è l'indice secondario per username. `getById(int)` legge `byId` direttamente (id mai rinominato → risoluzione sempre valida, anche dopo un cambio username in corso di partita).
- `GameManager`: `history` (`ConcurrentHashMap<Integer, GameHistory>`) tiene lo storico delle partite concluse indicizzato per `roundId`; `onlineUsers` è un `ConcurrentHashMap.newKeySet()` (set concorrente) degli utenti loggati, usato per l'auto-join alla rotazione.
- `ActiveGame`: `players` (`ConcurrentHashMap<Integer, PlayerState>`) mappa userId → stato di gioco del giocatore.
- `UdpRegistry`: `endpoints` (`ConcurrentHashMap<Integer, Endpoint>`) mappa userId → `addr:port` UDP.

**Limite della `ConcurrentHashMap`**: garantisce l'atomicità di ciascuna **singola** operazione (get/put/remove) ma non offre alcuna atomicità **attraverso più strutture**. Quando un'operazione deve modificare coerentemente *due* strutture condivise (per es. aggiungere un utente ad `onlineUsers` e unirlo a `current`), la sola `ConcurrentHashMap` non basta: servono i lock `synchronized` descritti sotto, incluso il lock di metodo su `GameManager`. 

### Contatori atomici

- `AtomicInteger`: `UserStore.nextId` genera id utente monotoni; `GameManager.nextRoundId` assegna a ogni partita un `roundId` univoco (che non si ripete al ciclo del loader).
- `AtomicBoolean`: `ActiveGame.finalized` segnala la conclusione della partita. Lettura senza lock (volatile) nelle validazioni; scrittura unica atomica via `compareAndSet(false, true)` in `finalizeGame` per rendere la finalizzazione **idempotente** (solo la prima procede).
- `volatile`: `GameManager.current` (la partita attiva) è `volatile`; è costruita *prima* dell'assegnazione, quindi le letture senza lock vedono uno stato coerente.

### Lock `synchronized` (lock globale solo dove serve)

L'approccio evita un unico monitor sull'intero server e adotta granularità variabili, riservando una lock "globale" (per-manager) solo dove serve l'atomicità tra più strutture; altrove si sincronizza finemente, senza mettere un unico monitor sull'intero stato condiviso.

**Sul singolo GameManager (`synchronized(this)`)**, è la **lock "globale"** del manager: serializza le operazioni che devono essere atomiche **attraverso più strutture condivise** (principalmente `onlineUsers` e `current`). È un lock di metodo, quindi copre l'intero `GameManager`; è accettato perché queste operazioni sono rare (login/logout/rotazione/finalizzazione) e non sul percorso caldo delle submit:
- `rotate()`: crea la nuova partita e re-iscrive gli utenti online;
- `registerLogin()` / `registerLogout()` / `logoutUser()`: modifiche ad `onlineUsers` serializzate con `rotate()` per evitare che un login si unisca a una partita appena ruotata o a doppio;
- `finalizeGame()`: idempotente, chiude la partita, scrive storico e aggiorna le statistiche.

**Lock granulare su `PlayerState` (`synchronized(ps)`)**, il cuore del parallelismo: in `ActiveGame.submit()` N client possono presentare proposte **in parallelo**; ogni proposta tocca lo stato del singolo giocatore, quindi il lock è per-giocatore (`ps`), non sulla partita. Lo stesso vale per `wordsOfFoundGroups(ps)`, `outcomeOf(ps)` e la lettura dei contatori in `gameInfo`/`gameStats`. Un lock di partita (`synchronized(this)` su `submit`) avrebbe serializzato tutte le proposte: scartato.

**Sul singolo `User` (`synchronized(u)`)**, in `UserStore`, i campi mutabili di un account (password, statistiche) sono protetti dal monitor dell'oggetto `User`. Due utenti diversi non si bloccano mai a vicenda. Le operazioni di mappa (`register`, `login`, `getByName`, `updateCredentials`) si appoggiano a `synchronized(this)` sullo store per la fase di lookup/inserzione; l'aggiornamento dei campi avviene sotto `synchronized(u)`.

**Lock dedicato per il disco I/O (`ioLock`)**, sia `UserStore.persist()` sia `GameManager.persistHistory()` scrivono su un **monitor dedicato** (`ioLock`), separato da `this`. Così l'I/O (lento) non blocca register/login o il gameplay; le fonti di persistenza concorrenti si serializzano comunque tra loro su `ioLock`. La scrittura è atomica su file: si scrive su `file.tmp` e poi `Files.move(... ATOMIC_MOVE)`.

### Sincronizzazione della classifica

`leaderboard()` non usa un lock globale: cattura uno **snapshot** dei punteggi iterando gli utenti e leggendo `cumulativeScore` sotto `synchronized(u)` per singolo utente. È una "fotografia sfocata"; tra la lettura dei dati di Alice e quelli di Bob `finalizeGame()` potrebbe aggiornare alcune statistiche, ma è accettabile perché la classifica è **read-only** e così si evita un bottleneck di serializzazione con la finalizzazione.

## Lato client

Sul client lo stato condiviso si riduce alla connessione. `ClientConn.sendAndRetreive()` è **`synchronized`**: un unico lock copre scrittura della richiesta e lettura della risposta, così il thread main (CLI) e il thread `udp` (receiver) non intrecciano le righe JSON sul canale TCP.

Il resto dello stato UI (`Cli`) è manipolato solo dal thread main; `UdpClient` non condivide dati con la CLI oltre alla connessione (usa `Cli.renderGameInfo`, statico e stateless). Non esiste quindi altra struttura sincronizzata lato client.

