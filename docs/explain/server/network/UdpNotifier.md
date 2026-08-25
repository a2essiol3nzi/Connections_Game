# `network/UdpNotifier` — notifiche async di fine partita (UDP, unicast)

## Ruolo
Invia ai partecipanti, via UDP, la notifica di fine partita (§2.2, §3):
`GameScheduler` la usa per segnalare la chiusura. **Unicast** per partecipante
(no multicast, nuovo ordinamento), usando gli endpoint dal `UdpRegistry`.

## Campi
- `registry` — `UdpRegistry` (lookup userId → InetAddress:port).
- `GSON` — serializzazione payload.

## Metodo principale
- `notifyEnd(Set<Integer> participants, JsonObject payload)` — serializza il
  payload; apre **UN SOLO** `DatagramSocket` riusato per tutti i destinatari.
  Per ogni `userId` recupera l'endpoint dal registry (salta se assente),
  `sock.connect(addr, port)` ridefinisce il remoto, poi `send(pkt)`. Il
  `try/catch(IOException)` è **esterno** al loop (errore di apertura socket),
  così un fallimento verso un host irraggiungibile non interrompe gli altri
  (`connect` rende il `send` non bloccante: `PortUnreachableException` immediata
  invece del timeout OS).

## Note
Endpoint ricavato lato server: IP dal socket TCP + port fornito dal client al
login (vedi `ClientHandler`). `participants` è `Set<Integer>` (userId). Payload
`{type:"GAME_ENDED", gameId, roundId}` per distinguere l'esecuzione conclusa.

## Collegamenti
- `network/UdpRegistry`: unico stato degli endpoint.
- `network/GameScheduler`: unico chiamante.
- `core/ServerMain`: crea notifier+registry nel `Context`.
- `core/GameManager`/`ActiveGame`: forniscono `participants()`, `gameId`, `roundId`.
