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
   Errore → `System.exit(3)`.
3. **Risorse condivise** — `new Context(cfg, loader)`.
4. **Thread supporto** — `GameScheduler` e `PersistenceThread` (oggetti `Runnable`
   avviati come `Thread`).
5. **Shutdown hook** — `Runtime.addShutdownHook(...)`: su SIGTERM/SIGINT chiama
   `ctx.users.persist()` (salva gli utenti prima di uscire, nessuna perdita
   dell'ultima partita finalizzata).
6. **Acceptor TCP** — `Executors.newFixedThreadPool(cfg.poolSize)` +
   `ConnectionAcceptor.run()` sul main thread.

## Dipendenze
`ServerConfig`, `GameLoader`, `Context`, `ConnectionAcceptor`, `GameScheduler`,
`PersistenceThread`. Nessuna logica di gioco qui: solo wiring.

## Collegamenti
- `core/ServerConfig`: parametri da `.properties`.
- `core/Context`: raggruppa loader/store/manager/notifier.
- `network/ConnectionAcceptor`: ciclo accept∞.
- `network/GameScheduler` + `persistence/PersistenceThread`: thread di servizio.
- `persistence/UserStore`: lo shutdown hook ne chiama `persist()`.
