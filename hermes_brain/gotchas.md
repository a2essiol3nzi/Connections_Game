# GOTCHAS & DECISIONI — Server Connections

## Regole ferree (vincoli progetto)
- **Concorrenza**: solo `synchronized` / `wait()`/`notifyAll()` / `java.util.concurrent.atomic.*`.
  VIETATI `java.util.concurrent.locks.*` (ReentrantLock, ReadWriteLock, …).
- **JSON**: GSON 2.11.0 (`lib/gson-2.11.0.jar`), non hand-rolled.
- **NIO**: lato CLIENT obbligatorio (§3); server usa I/O bloccante (ammesso).
- **UDP**: notifiche async fine partita; **unicast** (no multicast, nuovo ordinamento).
- **File partite**: schema array top-level `[{"gameId":int,"groups":[{"theme":str,"words":[4]}]}]`;
  `theme` MAI al client. ~620KiB fornito dai docenti (non nel repo → fixture in `data/games.json`).
- **Persistenza**: JSON utenti; periodica + atomica (tmp+rename).
- **Naming**: classi con `main` contengono "Main" (§4). Sorgenti in subpackage.
- **Config**: da `.properties`, NO CLI né interattivo.

## Scelte confermate (default doc PDF)
- Leaderboard = **cumulative score**. Gap tra partite = **immediato** (nessun ritardo).
- `gameId = -1` ⇒ partita corrente. Durata default 600s.

## Bug caught da verifica
- `GameLoader` streaming con `CountingInputStream` sotto `InputStreamReader` bufferizzato
  → offset errati (EOF). Fix: `RandomAccessFile` + scansione span brace-depth + parse per-oggetto.
- `UserStore.login/register/updateCredentials` ritornavano `"OK"` (stringa) → `ClientHandler`
  li trattava come errore (`!= null`). Fix: ritornano `null`=OK, stringa=errore (convenzione unica).

## Note utente (stile)
- Commenti estesi OK; lui li sintetizzerà. Mantenere filosofia di scrittura attuale.
- Password in chiaro: semplificazione voluta (non focalizza sicurezza).
- Id utente immutabile (consiglio prof): `<id,User>` primaria + `<username,id>` indice.
