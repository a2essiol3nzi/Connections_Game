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
  **`roundId`**.
  - `GameHistory { roundId, sourceGameId, GameData.Group[4] (tema+parole),
    Map<Integer,HistoryEntry> entries }` — `entries` chiave = **userId immutabile**.
  - `HistoryEntry { correct, errors, score, outcome }`.
- `onlineUsers` — `Set<Integer>` (ConcurrentHashMap-backed) per auto-join.
- `nextRoundId` — `AtomicInteger` monotono (non si ripete al wrap).
- `HISTORY_CAP = 10_000` (trim del `roundId` più basso).
- `historyFile` + `ioLock` (monitor dedicato per I/O su disco).

## Operazioni (identificazione per **userId** immutabile)
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
- `leaderboard(playerName, topK, store)` → **`LeaderboardPayload`** — snapshot
  `(id,score)` con lock granulare per utente, sort+rank DOPO; weak consistency
  accettata (read-only). `null` ⇒ `ERR_PLAYER_NOT_FOUND`.
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
"persistenza su file JSON" e lo storico è bounded (10k, ~MB) e cambia UNA volta
per partita → riscrittura integrale adeguata (niente early-optimization). Gson
streamed verso il writer: O(1) di memoria extra.

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