# `core/ServerMain` — entry point del server

## Ruolo
Avvia il server: config → loader → Context → thread di supporto → shutdown hook
→ acceptor TCP sul thread principale.

## Sequenza di avvio
1. **Config**: `ServerConfig.load(cfgPath)` (default `server.properties`; su
   `IOException` → `System.exit(2)`).
2. **Loader**: `new GameLoader(cfg.gamesFile)` (streaming Gson, memoria O(1): le
   partite non stanno mai tutte in RAM); su errore → `System.exit(3)`.
3. **Context**: `new Context(cfg, loader)` (risorse condivise: `users`, `games`,
   `udpRegistry`, `notifier`).
4. **Thread di supporto**: `GameScheduler` (thread "scheduler") e
   `PersistenceThread(ctx.users, cfg.persistIntervalSec)` (thread "persist").
5. **Shutdown hook**: su SIGTERM/SIGINT salva `users.persist()` +
   `games.persistHistory()` → nessuna perdita dell'ultima partita finalizzata.
6. **Acceptor TCP**: `ExecutorService` fixed pool (`cfg.poolSize`) +
   `ConnectionAcceptor` sul thread principale (bloccante).

## Note
- `PersistenceThread` riceve **solo `UserStore`** (non più `GameManager`): il
  timer salva solo gli utenti; lo storico partite lo salvano scheduler
  (post-finalize) e shutdown hook.
- Pool size = numero massimo di connessioni/concorrenti servite
  contemporaneamente (una connessione persistente occupa un thread).

## Collegamenti
- `core/ServerConfig`, `core/Context`, `core/GameManager`, `core/UserStore`.
- `loader/GameLoader`.
- `network/GameScheduler`, `network/ConnectionAcceptor`.
- `persistence/PersistenceThread`.