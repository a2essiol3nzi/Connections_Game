# GOTCHAS & DECISIONI — Server Connections

## Regole ferree (vincoli progetto)
- **Concorrenza**: solo `synchronized` / `wait()`/`notifyAll()` / `java.util.concurrent.atomic.*`.
  VIETATI `java.util.concurrent.locks.*` (ReentrantLock, ReadWriteLock, …).
- **JSON**: GSON 2.11.0 (`lib/gson-2.11.0.jar`), non hand-rolled.
- **NIO**: lato CLIENT obbligatorio (§3); server usa I/O bloccante (ammesso).
- **UDP**: notifiche async fine partita; **unicast** (no multicast, nuovo ordinamento).
- **File partite**: schema array top-level `[{"gameId":int,"groups":[{"theme":str,"words":[4]}]}]`;
  `theme` MAI al client (finisce nello storico `groupInfo`, non nelle info live). ~620KiB fornito dai docenti.
- **Persistenza**: JSON utenti **+ JSON storico partite**; periodica + atomica (tmp+rename). Due file separati.
- **Naming**: classi con `main` contengono "Main" (§4). Sorgenti in `core/loader/model/network/persistence/protocol`.
- **Config**: da `.properties`, NO CLI né interattivo.

## Scelte confermate (default doc PDF)
- Leaderboard = **cumulative score**. Gap tra partite = **immediato** (auto-join degli `onlineUsers` alla rotazione).
- `roundId = -1` (non `gameId`) ⇒ partita corrente nelle richieste. `gameId` sorgente si ripete al wrap; `roundId` è monotono e univoco.
- Identificazione **per userId immutabile** ovunque (storico, leaderboard, handler): sopravvive alla rinomina in partita.
- Durata default 600s.

## Bug caught da verifica
- `GameLoader` streaming con `CountingInputStream` → offset errati (EOF). Fix: `RandomAccessFile` + span scan → poi **semplificato in `JsonReader`+`skipValue`** (O(1), robusto sugli oggetti malformati a runtime).
- `UserStore.login/register/updateCredentials` ritornavano `"OK"` (stringa) → `ClientHandler` li trattava come errore. Fix: ritornano `null`=OK, stringa=errore (convenzione unica).
- `UserStore.load()` catch solo `IOException` → JSON malformato (`JsonSyntaxException`) faceva non partire il server. Fix: `catch (Exception e)` + rinomina `.corrupt` + partenza vuota.
- **Race submit/join vs rotate**: lo scheduler poteva finalizzare mentre un handler valutava. Fix: `ActiveGame.finalized` (`AtomicBoolean`) sigilla `submit`/`join` dopo la rotazione.
- **Storico per username (B9)**: la rinomina in partita invalidava l'accesso allo storico. Fix: chiave dello storico = **userId immutabile** (campo `userId` in `PlayerState`, `entries` per id in `GameHistory`).

## Note utente (stile)
- Commenti estesi OK; lui li sintetizzerà. Mantenere filosofia di scrittura attuale.
- Password in chiaro: semplificazione voluta (non focalizza sicurezza).
- Id utente immutabile (consiglio prof): `<id,User>` primaria + `<username,id>` indice; `getById` per risoluzione stabile.
