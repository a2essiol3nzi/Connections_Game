# `core/GameManager` — cuore della logica di gioco

## Ruolo
Mantiene l'UNICA partita attiva e, a ogni rotazione, ne chiede UNA ALLA VOLTA al
`GameLoader` (pigro, ciclico). Espone le operazioni al `ClientHandler` (join,
submit, info/stats, classifica, stats utente), **registra gli esiti in uno
storico PERSISTITO** e aggiorna le statistiche (`UserStore`).

## Stato interno
- `loader`, `durationSec`, `rnd`, `games` (`CyclicGameIterator`).
- `current` — `ActiveGame` globale (null solo se loader vuoto).
- `history` — `ConcurrentHashMap<Integer,GameHistory>` partite CONCLUSE, chiave =
  **`roundId`**.
  - `GameHistory { roundId, sourceGameId, GameData.Group[4] (tema+parole),
    Map<Integer,HistoryEntry> entries }` — `entries` chiave = **userId immutabile**.
  - `HistoryEntry { correct, errors, score, outcome }`.
- `onlineUsers` — `Set<Integer>` (ConcurrentHashMap-backed) per auto-join.
- `nextRoundId` — `AtomicInteger` monotono (non si ripete al wrap).
- `HISTORY_CAP = 10_000` (trim del `roundId` più basso).
- `historyFile`.

## Operazioni (identificazione per **userId** immutabile)
- `registerLogin(userId)` / `registerLogout(userId)` — registry online
  (`synchronized`, atomico vs `rotate`).
- `logoutUser(userId, UdpRegistry)` — rimuove da online **e** da UDP registry
  (logout atomico).
- `join(userId)`, `submitProposal(userId, words)` — `submitProposal` è
  **`synchronized`** (serializza con `rotate`, evita TOCTOU tra `current()` e
  `submit()`).
- `gameInfo(userId, roundId, store)` → **`GameInfoPayload`** —
  `roundId==-1` ⇒ corrente (live: id, `sourceGameId`, `remainingSec`, stato,
  `remainingWords`); `roundId!=-1` ⇒ storico (`assignment`+tema + esito del
  giocatore). Ritorna `null` su errore (handler →
  `ERR_NO_ACTIVE_GAME`/`ERR_GAME_NOT_FOUND`).
- `gameStats(roundId)` → **`GameStatsPayload`** — live (in corso/finiti/vinti +
  `remainingSec`) o da `history` (media).
- `leaderboard(playerName, topK, store)` → **`LeaderboardPayload`** — snapshot
  `(id,score)` con lock granulare per utente, **sort + rank DOPO** lo snapshot;
  weak consistency accettata (read-only). `null` se `playerName` inesistente ⇒
  `ERR_PLAYER_NOT_FOUND`.
- `playerStats(userId, store)` → **`PlayerStatsPayload`** — snapshot di TUTTI i
  campi (+copia `mistakeHistogram`) sotto `synchronized(u)` per evitare dirty
  read. `null` ⇒ `ERR_USER_NOT_FOUND`.
- `finalizeGame(store)` — **idempotente** (`finalized.compareAndSet(false,true)`):
  sigilla, scrive `GameHistory` (chiave userId), aggiorna statistiche utente.
- `persistHistory()` / `loadHistory()` — JSON atomico (tmp+`ATOMIC_MOVE`);
  `loadHistory` ripristina `nextRoundId`.

## Concorrenza
`rotate`, `current`, `registerLogin`, `registerLogout`, `submitProposal`,
`finalizeGame`, `persistHistory` sono `synchronized`. `submit` in `ActiveGame`
usa lock granulare su `PlayerState`. `finalized` `AtomicBoolean`. Lo storico è
`ConcurrentHashMap`. `leaderboard`/`playerStats` leggono le statistiche sotto
`synchronized(u)` (paired col writer in `finalizeGame`) ma con **weak consistency**
tra utenti diversi (snapshot sfocata accettabile per query read-only). Solo
`synchronized`/`Atomic*`/`ConcurrentHashMap` (NO `locks.*`).

## Collegamenti
- `protocol/payload/*`: tipi di ritorno (`GameInfoPayload`, `GameStatsPayload`,
  `LeaderboardPayload`, `PlayerStatsPayload`).
- `core/ActiveGame`/`PlayerState`: crea e interroga.
- `loader/GameLoader` + `CyclicGameIterator`: sorgente.
- `core/UserStore`: storico per userId, statistiche.
- `network/GameScheduler`: `rotate`/`finalizeGame`/`persistHistory`.
- `core/ServerMain` + `persistence/PersistenceThread`: persistenza/shutdown.
- `network/UdpRegistry`: `logoutUser` lo pulisce.
