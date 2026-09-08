# `ClientMain` — entry point del client

## Ruolo
Punto di ingresso (`main`). Orchestrano l'avvio del client: legge config, apre
la connessione **NIO TCP**, crea il **receiver UDP** (porta effimera), avvia il
thread di notifiche e infine esegue il loop CLI sul thread principale.

## Sequenza di avvio
1. `ClientConfig.load("client.properties")`; su errore → `exit(2)`.
2. `new ClientConn(cfg.host, cfg.tcpPort)` — connessione TCP persistente NIO.
3. `new UdpClient(conn)` — **bind UDP su porta effimera** (necessario PRIMA del
   login: la porta va inviata al server come `udpPort`).
4. `new Thread(udp, "udp").start()` — avvia il ricevitore notifiche.
5. `new Cli(conn, udp.port()).run()` — loop CLI (bloccante) sul main thread.

`ClientConn` e `UdpClient` sono `AutoCloseable`: chiusi via try-with-resources
alla fine (anche su `IOException` di connessione → `exit(1)`).

## Note
La porta UDP è **non configurabile** (effimera col socket receiver): il valore
viene passato a `Cli`, che lo mette in ogni `login`.

## Collegamenti
- `client/ClientConfig`: host/TCP port.
- `client/ClientConn`: connessione NIO utilizzata da CLI e UDP.
- `client/UdpClient`: receiver notifiche (port da inviare al server).
- `client/Cli`: loop dei comandi.