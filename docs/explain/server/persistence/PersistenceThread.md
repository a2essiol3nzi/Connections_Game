# `persistence/PersistenceThread` — persistenza periodica

## Ruolo
Thread che salva gli **utenti** su JSON a intervalli fissi. NOTA: lo storico
partite NON viene più salvato qui — cambia una sola volta per partita e lo
salvano già `GameScheduler` (subito dopo `finalizeGame`) e lo shutdown hook in
`ServerMain`. Il timer quindi non ha bisogno dello storico: evitava uno stallo
inutile ogni `intervalSec`.

## Stato interno
- `users` (`UserStore`), `intervalSec`.

## Flusso
`while(true)`: `sleep(intervalSec*1000)` → `users.persist()` → log. Su
`InterruptedException` imposta l'interrupt e termina; su altre `Exception` logga
e continua.

## Concorrenza
Nessuno stato proprio condiviso: delega la sincronizzazione a `users.persist()`
(che serializza l'I/O su `ioLock`, fuori dal monitor `this` dello store).

## Collegamenti
- `core/UserStore`: `persist()`.
- `core/ServerMain`: istanzia il thread.
- `network/GameScheduler`: anche lui salva utenti (post-finalize), event-driven;
  i due si serializzano su `UserStore.ioLock`.