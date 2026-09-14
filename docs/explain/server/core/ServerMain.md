# `core/ServerMain` — entry point del server

## Ruolo
Avvia il server: config → loader → Context → thread di supporto → shutdown hook
→ acceptor TCP sul thread principale.

## Sequenza di avvio
1. **Config**: `ServerConfig.load("server.properties")`; su `IOException` →
   `System.exit(2)`.
2. **Loader**: `new GameLoader(cfg.gamesFile)` (streaming Gson, memoria O(1): le
   partite non stanno mai tutte in RAM); su errore → `System.exit(3)`.
3. **Context**: `new Context(cfg, loader)` (risorse condivise: `users`, `games`,
   `udpRegistry`, `notifier`).
4. **Scheduler**: `GameScheduler` possiede un `ScheduledExecutorService` con
   worker `scheduler`. `start()` programma la prima scadenza. **Nessun
   `PersistenceThread`**: la persistenza è event-driven (vedi sotto).
5. **Acceptor TCP**: pool **on-demand** (`ThreadPoolExecutor`, core 0 → max `cfg.poolSize`,
   keep-alive 10s, `SynchronousQueue`, policy di rifiuto AbortPolicy) +
   `ConnectionAcceptor` sul thread principale (bloccante).
6. **Shutdown hook**: su SIGTERM/SIGINT chiude acceptor e socket client,
   ferma/attende scheduler e pool, quindi salva `users.persistUsers()` +
   `games.persistHistory()`. La persistenza finale non corre contro mutatori.

## Note
- **Persistenza event-driven (niente timer)**: i deltas account (register/rename)
  si salvano in `UserStore.register`/`updateCredentials` via `persistQuiet()`; le
  stats/storico li salva lo scheduler post-`finalizeGame`. Il solo shutdown hook
  copre l'ultima partita finalizzata all'uscita, dopo l'arresto ordinato di
  scheduler, acceptor e pool.
- Pool size = numero massimo di connessioni/concorrenti servite
  contemporaneamente (una connessione persistente occupa un thread).

## Collegamenti
- `core/ServerConfig`, `core/Context`, `core/GameManager`, `core/UserStore`.
- `loader/GameLoader`.
- `network/GameScheduler`, `network/ConnectionAcceptor`.