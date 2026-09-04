# `core/GameManager` — cuore della logica di gioco

## Ruolo
Mantiene l'UNICA partita attiva e, a ogni rotazione, ne chiede UNA ALLA VOLTA al
`GameLoader` (pigro, ciclico). Espone le operazioni al `ClientHandler` (join,
submit, info/stats, classifica, stats utente), **registra gli esiti in uno
storico PERSISTITO** e aggiorna le statistiche (`UserStore`).

## Stato interno
- `loader`, `durationSec`, `rnd`, `games` (`CyclicGameIterator`).
- `current` — `ActiveGame` **`volatile`**: visibilità cross-thread senza lock in
  lettura; assegnato SOLO in `rotate()` (synchronized) e nel costruttore, sempre
  costruito PRIMA della pubblicazione → nessun oggetto "semivisto".
- `history` — `ConcurrentHashMap<Integer,GameHistory>` partite CONCLUSE, chiave =
  **`roundId`**, **TENUTA IN RAM** per servire richieste storiche arbitrarie.
  - `GameHistory { roundId, sourceGameId, GameData.Group[4] (tema+parole),
    Map<Integer,HistoryEntry> entries }` — `entries` chiave = **userId immutabile**.
  - `HistoryEntry { correct, errors, score, outcome }`.
- `onlineUsers` — `Set<Integer>` (ConcurrentHashMap-backed) per auto-join.
- `nextRoundId` — `AtomicInteger` monotono (non si ripete al wrap).
- `historyFile` + `ioLock` (monitor dedicato per I/O su disco). **Storico senza
  limite**: nessuna cap/trim (richiesta di progetto); cresce indefinitamente via
  `history.put` in `finalizeGame`.

## Operazioni (identificazione per **userId** immutabile)
- Costruttore — dopo `loadHistory()` chiama **`resumeFromRound()`** (vedi sotto),
  poi `makeNext()` crea la partita corrente.
- `resumeFromRound()` (privato, solo al boot) — **anti-furto ordine**: la sequenza
  del ciclo è deterministica (`0..total-1` poi wrap), 1 partita per round. Al
  riavvio riallinea l'iteratore al punto lasciato: `skip = (nextRoundId-1) % total`
  (= `roundId % total`, dove `roundId` = round già giocati da `loadHistory`),
  avanzando e scartando quelle partite. Così si riparte dalla partita successiva
  all'ultima mostrata prima dello shutdown, non dalla prima del file.
- `current()` — lettura **lock-free** (campo `volatile`). Sbloccata la path di
  lettura (`gameInfo`/`gameStats`/`join`/`submitProposal`).
- `rotate(nowMs)` — `synchronized`: `current = makeNext()` + auto-join di tutti
  gli online. `makeNext()` fa I/O (loader.next) sotto il monitor, ma una volta
  per partita (~600s) → `ponytail:` impatto trascurabile; fix quando misurabile
  (= costruire la next fuori dal monitor nello scheduler e qui solo swap+auto-join).
- `registerLogin` / `registerLogout` / `logoutUser` — `synchronized` (registry
  online atomico vs `rotate`).
- `join(userId)` — lock-free (usa `current()`).
- `submitProposal(userId, words)` — **NON più `synchronized`** (era il collo di
  bottiglia): la TOCTOU tra `current()` e `submit()` è gestita a valle da
  `ActiveGame.finalized` (AtomicBoolean) + `finalizeGame` idempotente — un submit
  sull'ex-partita al rotate torna `ERR_FINISHED` (semantica sana). Il vero
  parallelismo viene dal lock granulare su `PlayerState` in `ActiveGame.submit`.
- `gameInfo(userId, roundId, store)` → **`GameInfoPayload`** —
  `roundId==-1` (default) **o round corrente** ⇒ live; altro round ⇒ storico.
  `null` su errore (handler → `ERR_NO_ACTIVE_GAME`/`ERR_GAME_NOT_FOUND`).
- `gameStats(roundId)` → **`GameStatsPayload`** — live o da `history` (media).
  **Live**: `participantsTotal` conta SOLO i giocatori **ancora online** (connessi) e
  nella partita (`onlineUsers ∩ players`); chi si disconnette non conta più, pur
  restando in `players` (non va ripulita: `finalizeGame` la itera per storico +
  stats cumulative). `inProgress`/`finished`/`won` tra i presenti.
- `leaderboard(playerName, topK, requesterId, store)` → **`LeaderboardPayload`** — snapshot
  `(id,score)` con lock granulare per utente, sort+rank DOPO; weak consistency
  accettata (read-only). La riga con `id == requesterId` porta `requester=true`
  per la resa client; `null` ⇒ `ERR_PLAYER_NOT_FOUND`.
- `playerStats(userId, store)` → **`PlayerStatsPayload`** — snapshot di TUTTI i
  campi sotto `synchronized(u)`. `null` ⇒ `ERR_USER_NOT_FOUND`.
- `finalizeGame(store)` — `synchronized`, **idempotente**
  (`finalized.compareAndSet(false,true)`): sigilla, scrive `GameHistory` (chiave
  userId), aggiorna statistiche utente. Legge lo stato di ogni giocatore sotto
  **`synchronized(ps)`** esplicito (necessario: con `submitProposal` non più
  serializzato dal monitor, protegge da submit concorrenti in finalizzazione).
- `persistHistory()` — NON `synchronized`; **streamed** `GSON.toJson(history, w)`
  direttamente sul writer (nessuna `String`/`JsonElement` intermedia dello
  storico: memoria extra O(1)); I/O su `ioLock` dedicato, non blocca il gameplay.
  L'`history.put` avviene solo in `finalizeGame` (dallo scheduler, unico
  scrittore) → nessun lock aggiuntivo per lo snapshot.
- `loadHistory()` — ripristina `history` e `nextRoundId`.

## Concorrenza
`rotate`, `registerLogin`, `registerLogout`, `logoutUser`, `finalizeGame` sono
`synchronized`. `current()` / `join()` / `submitProposal()` sono **lock-free**
(campo `volatile` + `AtomicBoolean` + lock granulare in `ActiveGame`). `submit`
in `ActiveGame` usa lock per-`PlayerState`; `finalizeGame` legge `ps` sotto
`synchronized(ps)`. Lo storico è `ConcurrentHashMap`; `history` ha UNICO
scrittore (scheduler via `finalizeGame`). `leaderboard`/`playerStats` leggono le
stat sotto `synchronized(u)` (weak consistency tra utenti, accettabile per query
read-only). Solo `synchronized`/`volatile`/`Atomic*`/`ConcurrentHashMap`
(NO `locks.*`).

## Persistenza storico (scelta didattica)
Storico intero riscritto su file JSON atomico (tmp + `ATOMIC_MOVE`). In
produzione si userebbe un DB append-only/WAL; qui il vincolo del progetto è
"persistenza su file JSON" e lo storico cresce SENZA limite (nessun trim) — il
file si allarga di un round (~KB) a ogni partita → riscrittura integrale adeguata
per questo scope (niente early-optimization). Gson streamed verso il writer:
O(1) di memoria extra.

**Tradeoff RAM / boot (implicito nel requisito "senza limite")**: lo storico è
**tenuto interamente in RAM** (`history` CHM) per servire richieste arbitrarie;
costa poche centinaia di KB/giorno (~160 round a 600s/partita) → irrilevante per
una JVM, ma il costo reale crescente è (a) il **file** e (b) il **boot** —
`loadHistory` deserializza tutto in RAM all'avvio. Entrambi accettati per scope
didattico; con storico illimitato in produzione si userebbe un DB (WAL/append) e
un indice lazy per-round, che eviterebbero RAM + rewrite-integrali (vedi commenti
in `GameManager.java` su `history` e `loadHistory`).

## Collegamenti
- `protocol/payload/*`: tipi di ritorno (`GameInfoPayload`, `GameStatsPayload`,
  `LeaderboardPayload`, `PlayerStatsPayload`).
- `core/ActiveGame`/`PlayerState`: crea e interroga.
- `loader/GameLoader` + `CyclicGameIterator`: sorgente.
- `core/UserStore`: storico per userId, statistiche.
- `network/GameScheduler`: `rotate`/`finalizeGame`/`persistHistory`/`persist` utenti.
- `core/ServerMain`: persistenza/shutdown (shutdown hook: `persist` + `persistHistory`;
  avvio scheduler). L'event-driven dei deltas account sta in
  `UserStore.register`/`updateCredentials` (`persistQuiet()`).
- `network/UdpRegistry`: `logoutUser` lo pulisce.