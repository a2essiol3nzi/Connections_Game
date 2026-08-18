# Documentazione componente: `core/GameManager` (logica di gioco)

## Responsabilità
Cuore della logica di gioco server-side:
- mantiene l'**unica partita globale attiva** (`ActiveGame`);
- la ruota allo scadere, chiedendo UNA partita al loader pigro
  (`gameAt(indice ciclico)`) — NON tiene tutte le partite in RAM;
- espone le operazioni richieste dal `ClientHandler`
  (join, submitProposal, info/stats partita, classifica, statistiche utente);
- registra gli esiti a fine partita e aggiorna le statistiche utente.

## Campi
| Campo | Ruolo |
|-------|-------|
| `loader` | sorgente partite PIGRO (`loader.GameLoader`) |
| `durationSec` | durata partita (da config) |
| `current` | partita globale attiva (null solo se loader vuoto) |
| `nextIdx` | indice ciclico della prossima partita |
| `history` | storico partite concluse: `gameId → (username → esito/score)` |

`GameHistory`/`HistoryEntry`: esiti di ogni giocatore per partita finita
(`correct`, `errors`, `score`, `outcome`).

## Operazioni (chiamate da `ClientHandler`)
- `join(username)` → entra nella partita corrente (null = OK, altrimenti `Errors`).
- `submitProposal(username, words)` → delega ad `ActiveGame`.
- `gameInfo(username, gameId)` → stato/esito (`gameId==-1` = corrente).
- `gameStats(gameId)` → **in corso**: conteggi live (in corso/finiti/vinti);
  **storica**: da `history` (partecipanti, vinti, media).
- `leaderboard(playerName, topK, store)` → utenti per `cumulativeScore`
  (tutti/top-K), opz. rango.
- `playerStats(username, store)` → statistiche NYT-style.
- `finalizeGame(store)` → a fine partita: salva esiti + aggiorna stats utente.

## Nota `games.toArray` (versione precedente)
Eliminata: il manager ora non riceve una `List<GameData>` ma il loader pigro;
a ogni `rotate` pesca `gameAt(nextIdx % total)`.

## Concorrenza
Swap di `current` in metodi `synchronized` (vincolo: NO `java.util.concurrent.locks.*`).
Valutazione delegata ad `ActiveGame` (a sua volta `synchronized`).

## Collegamenti
- `core/ActiveGame`: valutazione proposta.
- `loader/GameLoader`: sorgente partite.
- `network/ClientHandler`: chiama questi metodi.
- `network/GameScheduler`: chiama `rotate(...)`/`finalizeGame(...)`.
