# `network/ConnectionAcceptor` — accept TCP + thread pool

## Ruolo
Accetta connessioni TCP e le smista ai thread del pool (thread pooling, §3).
Una connessione per client resta aperta per tutta la sessione (persistente,
line-based JSON).

## Campi
- `port` — porta di listen (da `ServerConfig.tcpPort`).
- `pool` — `ExecutorService` (da `ServerMain`, `cfg.poolSize`).
- `ctx` — `Context` condiviso (incluso `udpRegistry`).

## Metodo principale
- `run()` — apre `ServerSocket(port)`, ciclo `accept()` infinito; ad ogni `Socket`
  client fa `pool.submit(new ClientHandler(client, ctx))`. Su `IOException`
  stampa e termina.

## Concorrenza
Il ciclo `accept` gira sul **thread principale** (`ServerMain`). Ogni connessione
è gestita da un thread del pool: lo stato per-connessione vive in `ClientHandler`
(nessuna condivisione tra handler).

## Collegamenti
- `core/ServerMain`: crea il pool e l'acceptor, chiama `run()`.
- `network/ClientHandler`: task eseguito per ogni connessione.
- `core/Context`: passato agli handler per store/manager/notifier/registry.
