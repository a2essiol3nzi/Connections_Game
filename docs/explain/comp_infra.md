# Documentazione componente: `persistence/` + `core/ServerConfig`

## `persistence/PersistenceThread`
Thread di salvataggio periodico: chiama `UserStore.persist()` a intervalli
(da `server.properties`, `persist.interval.sec`). Gestisce
`InterruptedException` (termina pulito) e errori di I/O. Implementa la
persistenza periodica richiesta da §2.2.

## `core/ServerConfig`
Carica i parametri da `server.properties` (o path passato come argomento a
`ServerMain`). Campi `public final` tipizzati, immutabili dopo il load:
`tcpPort`, `udpPort`, `gameDurationSec`, `poolSize`, `persistIntervalSec`,
`gamesFile`, `persistFile`.

## `core/Context`
Contenitore delle risorse condivise (evita singleton globali): istanzia
`UserStore`, `GameManager` (con il loader pigro), `UdpNotifier`. Passato per
referenza a handler/scheduler.

## `core/ServerMain`
Entry point. Sequenza: config → loader pigro (indicizzazione) → Context →
avvia scheduler + persist → acceptor TCP (bloccante). Eccezioni di avvio
gestite esplicitamente con messaggi chiari + exit code (no `throws Exception`).

## Collegamenti
- Tutti i pacchetti: `loader`, `protocol`, `core`, `network`, `persistence`.
