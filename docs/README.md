# Indice documentazione componenti — Server Connections

Struttura sorgente (`src/server/`):
```
server/
  core/       logica di gioco + infrastruttura
    ServerMain.java      entry point
    ServerConfig.java    parametri da .properties
    Context.java         risorse condivise
    GameManager.java     logica di gioco (rotazione lazy, stats, classifica)
    ActiveGame.java      partita attiva + valutazione proposte
    PlayerState.java     stato giocatore nella partita
  loader/     caricamento partite
    GameLoader.java      loader PIGRO (indicizzazione span + gameAt on-demand)
  persistence/ salvataggio/caricamento stato
    UserStore.java       utenti (id immutabile, per-account lock, psw chiara)
    PersistenceThread.java  salvataggio periodico
  network/    comunicazione socket
    ConnectionAcceptor.java  accept + thread pool
    ClientHandler.java       dispatch 9 operazioni
    UdpNotifier.java         notifiche async fine partita
    GameScheduler.java       rotazione partite
  protocol/   messaggi client<->server
    Errors.java    enum codici errore centralizzati
    Request.java   envelope richiesta
    Response.java  envelope risposta
  model/      POJO dati
    GameData.java  schema partita (gameId, groups[theme,words])
```

## File di documentazione per componente (in `docs/`)
- `comp_config.md` → ServerConfig / Context / ServerMain / PersistenceThread
- `comp_gameloader.md` → loader pigro
- `comp_userstore.md` → UserStore (id, lock, password, persist)
- `comp_gamemanager.md` → GameManager
- `comp_activegame.md` → ActiveGame / PlayerState
- `comp_protocol.md` → Errors / Request / Response
- `comp_network.md` → ConnectionAcceptor / ClientHandler / UdpNotifier / GameScheduler
- `comp_infra.md` → infrastruttura (config, persist, context, main)

## Convenzioni
- Successo = ritorno `null`; errore = codice stringa (vedi `protocol/Errors`).
- Concorrenza: solo `synchronized` / `wait`/`notify` / `Atomic*` (NO `locks.*`).
- IO: java.nio con charset UTF-8 esplicito.
