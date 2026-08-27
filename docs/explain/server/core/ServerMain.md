# `core/ServerMain` — entry point del server

## Ruolo
Punto di ingresso (`main`). Orchestrano l'avvio: legge config, crea il loader
pigro delle partite, costruisce il `Context` (risorse condivise), avvia gli
thread di supporto (scheduler, persistenza), **registra lo shutdown hook** e
infine apre l'acceptor TCP sul thread principale (bloccante).

## Sequenza di avvio
1. **Config** — `ServerConfig.load(path)`; `path` = `args[0]` o `"server.properties"`.
   Errore di lettura → `System.exit(2)`.
2. **Loader partite** — `new GameLoader(cfg.gamesFile)` (streaming Gson, O(1)).
   Errore → `System.exit(3)`. Log: `[Server] prepared <total> games from <file>`.
3. **Risorse condivise** — `new Context(cfg, loader)` (crea store/manager/registry UDP/notifier).
4. **Thread supporto** — `GameScheduler` (games/users/notifier) e `PersistenceThread`
   (users/**games**/intervalSec): entrambi avviati come `Thread`.
5. **Shutdown hook** — `addShutdownHook(...)`: su SIGTERM/SIGINT chiama
   `ctx.users.persist()` **e** `ctx.games.persistHistory()` (salva ultima
   partita + storico prima di uscire).
6. **Acceptor TCP** — `Executors.newFixedThreadPool(cfg.poolSize)` +
   `ConnectionAcceptor.run()` sul main thread.

## Dipendenze
`ServerConfig`, `GameLoader`, `Context`, `ConnectionAcceptor`, `GameScheduler`,
`PersistenceThread`. Nessuna logica di gioco qui: solo wiring.

## Collegamenti
- `core/ServerConfig` / `core/Context`: parametri e risorse.
- `network/ConnectionAcceptor`, `network/GameScheduler`, `persistence/PersistenceThread`:
  thread di servizio.
- `core/UserStore` + `core/GameManager`: lo shutdown hook li persiste.
