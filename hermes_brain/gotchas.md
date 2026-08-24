# GOTCHAS & DECISIONI — Server Connections

## Regole ferree (vincoli progetto)
- **Concorrenza**: solo `synchronized` / `wait()`/`notifyAll()` / `java.util.concurrent.atomic.*`.
  VIETATI `java.util.concurrent.locks.*` (ReentrantLock, ReadWriteLock, …).
- **JSON**: GSON 2.11.0 (`lib/gson-2.11.0.jar`), non hand-rolled.
- **NIO**: lato CLIENT obbligatorio (§3); server usa I/O bloccante (ammesso).
- **UDP**: notifiche async fine partita; **unicast** (no multicast, nuovo ordinamento).
- **File partite**: schema array top-level `[{"gameId":int,"groups":[{"theme":str,"words":[4]}]}]`;
  `theme` MAI al client live (finisce nello storico `groupInfo`, non nelle info live). ~620KiB fornito dai docenti.
- **Persistenza**: JSON utenti **+ JSON storico partite**; periodica + atomica (tmp+rename). Due file separati.
- **Naming**: classi con `main` contengono "Main" (§4). Sorgenti in `core/loader/model/network/persistence/protocol`.
- **Config**: da `.properties`, NO CLI né interattivo.

## Scelte confermate (default doc PDF)
- Leaderboard = **cumulative score**. Gap tra partite = **immediato** (auto-join degli `onlineUsers` alla rotazione).
- `roundId = -1` (non `gameId`) ⇒ partita corrente nelle richieste. `gameId` sorgente si ripete al wrap; `roundId` è monotono e univoco.
- Identificazione **per userId immutabile** ovunque (storico, leaderboard, handler, UDP registry): sopravvive alla rinomina in partita.
- Durata default 600s.

## Convenzione errori (REFACTOR)
- I metodi `UserStore`/`GameManager`/`ActiveGame` ritornano `null` = OK, oppure un valore
  `Errors` (enum). `ActiveGame.JoinResult`/`SubmitResult` incapsulano l'enum `Errors`
  (`error()`/`isOk()`), così `ClientHandler` mappa 1:1 senza letterali sparsi.
- `ERR_ALREADY_LOGGED_IN` è il codice per login duplicato: `GameManager.registerLogin`
  ritorna `false` se già online → handler risponde `ERR_ALREADY_LOGGED_IN`. Aggiunti
  `BAD_REQUEST` (JSON illeggibile/operation mancante/udpPort assente) e
  `ERR_PLAYER_NOT_FOUND`.

## Bug caught da verifica
- `GameLoader` streaming con `CountingInputStream` → offset errati. Fix: `RandomAccessFile`+span → poi **`JsonReader`+`skipValue`** (O(1), robusto su oggetti malformati a runtime).
- `UserStore.login/register/...` ritornavano `"OK"` (stringa) → trattati come errore. Fix: ritornano `null`=OK, `Errors`=errore (enum unico).
- `UserStore.load()` catch solo `IOException` → JSON malformato faceva non partire. Fix: `catch (Exception)` + rinomina `.corrupt` + partenza vuota.
- **Race submit/join vs rotate**: lo scheduler poteva finalizzare mentre un handler valutava. Fix: `ActiveGame.finalized` (`AtomicBoolean`) sigilla `submit`/`join` dopo la rotazione; `submitProposal`/`login` in `GameManager` sono `synchronized` (anti-TOCTOU tra `current()` e l'azione).
- **Lock di `submit` troppo grossolano**: `synchronized(this)` su `ActiveGame` serializzava tutte le proposte. Fix: **lock granulare su `PlayerState`** (`synchronized(ps)`) — N client sottomettono in parallelo; validazioni read-only (groups/shuffledWords immutabili) fuori lock; ri-check di `finalized` dentro il lock.
- **Storico per username (B9)**: la rinomina invalidava l'accesso allo storico. Fix: chiave dello storico = **userId immutabile** (`entries` per id in `GameHistory`).
- **Finalize multiplo**: scheduler + shutdown hook + timer potevano contare 2 volte. Fix: `finalizeGame` **idempotente** via `finalized.compareAndSet(false,true)`.

## Note utente (stile)
- Commenti estesi OK; lui li sintetizzerà. Mantenere filosofia di scrittura attuale.
- Password in chiaro: semplificazione voluta (non focalizza sicurezza).
- Id utente immutabile (consiglio prof): `<id,User>` primaria + `<username,id>` indice; `getById` per risoluzione stabile.
