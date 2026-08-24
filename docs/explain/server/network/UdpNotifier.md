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
  payload; per ogni `userId` recupera l'endpoint dal registry (salta se assente),
  crea `DatagramSocket` + `DatagramPacket(addr, port)` e invia. Errori di invio
  loggati, non propagati.

## Note
Endpoint ricavato lato server: IP dal socket TCP + port fornito dal client al
login (vedi `ClientHandler`). `participants` è `Set<Integer>` (userId). Payload
`{type:"GAME_ENDED", gameId, roundId}` per distinguere l'esecuzione conclusa.

## Collegamenti
- `network/UdpRegistry`: unico stato degli endpoint.
- `network/GameScheduler`: unico chiamante.
- `core/ServerMain`: crea notifier+registry nel `Context`.
- `core/GameManager`/`ActiveGame`: forniscono `participants()`, `gameId`, `roundId`.
