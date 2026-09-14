# `network/UdpNotifier` — notifiche async di fine partita (UDP, unicast)

## Ruolo
Invia ai partecipanti, via UDP, la notifica di fine partita (§2.2, §3):
`GameScheduler` la usa per segnalare la chiusura. **Unicast** per partecipante
(no multicast, nuovo ordinamento), usando gli endpoint dal `UdpRegistry`.

## Campi
- `registry` — `UdpRegistry` (lookup userId → InetAddress:port).
- `GSON` — serializzazione payload.

## Metodo principale
- `notifyEnd(Set<Integer> participants, Object payload)` — serializza il
  payload; apre **UN SOLO** `DatagramSocket` riusato per tutti i destinatari.
  Riusa anche il payload e **UN SOLO** `DatagramPacket`: per ogni `userId`
  con endpoint valido aggiorna soltanto la destinazione (`addr` e `port`) e lo
  invia, senza chiamare `connect`. UDP non attende conferme e non garantisce la
  consegna. Il `try/catch(IOException)` esterno gestisce il ciclo di vita del
  socket; ogni `send` e il relativo log di successo hanno un `try/catch`
  per-destinatario: un fallimento viene loggato con il `userId` e non impedisce
  i tentativi verso gli endpoint successivi.

## Note
Endpoint ricavato lato server: IP dal socket TCP + port fornito dal client al
login (vedi `ClientHandler`). `participants` è `Set<Integer>` (userId). Payload
`{type:"GAME_ENDED", gameId, roundId}` per distinguere l'esecuzione conclusa.

## Collegamenti
- `network/UdpRegistry`: unico stato degli endpoint.
- `network/GameScheduler`: unico chiamante.
- `core/ServerMain`: crea notifier+registry nel `Context`.
- `core/GameManager`/`ActiveGame`: forniscono `participants()`, `gameId`, `roundId`.
