# `network/ConnectionAcceptor` — accept TCP + thread pool

## Ruolo
Accetta connessioni TCP e le smista ai thread del pool (thread pooling, §3).
Una connessione per client resta aperta per tutta la sessione (persistente,
line-based JSON).

## Campi
- `port` — porta di listen (da `ServerConfig.tcpPort`).
- `pool` — `ThreadPoolExecutor` (da `ServerMain`, `cfg.poolSize`).
- `ctx` — `Context` condiviso (incluso `udpRegistry`).
- `clients` — socket accettati, per sbloccare i worker in shutdown.
- `serverSocket` / `shuttingDown` — listener pubblicato e flag di chiusura.

## Metodo principale
- `run()` — apre `ServerSocket(port)`, ciclo `accept()` infinito; ad ogni `Socket`
  client fa `pool.submit(new ClientHandler(client, ctx))`.
  - **Saturazione**: se il pool è al massimo (`AbortPolicy`), `submit` lancia
    `RejectedExecutionException`; l'acceptor la intercetta, logga
    `[Acceptor] pool saturo, rifiuto <IP>` e **chiude il socket** → il client
    viene disconnesso (non accumula task in coda, può ritentare).
  - Su `IOException` inattesa stampa e termina il processo; quella causata da
    `shutdown()` è attesa e chiude il loop senza errore.
- `shutdown()` chiude listener e socket client: sblocca sia `accept()` sia le
  `readLine()` degli handler, poi il pool può terminare prima della persistenza.

## Concorrenza
Il ciclo `accept` gira sul **thread principale** (`ServerMain`). Ogni connessione
è gestita da un thread del pool: lo stato per-connessione vive in `ClientHandler`
(nessuna condivisione tra handler).

## Collegamenti
- `core/ServerMain`: crea il pool e l'acceptor, chiama `run()`.
- `network/ClientHandler`: task eseguito per ogni connessione.
- `core/Context`: passato agli handler per store/manager/notifier/registry.
