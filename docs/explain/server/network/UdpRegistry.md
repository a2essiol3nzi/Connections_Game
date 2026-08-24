# `network/UdpRegistry` — registro endpoint UDP dei partecipanti

## Ruolo
Mappa **userId immutabile → endpoint UDP** (`InetAddress:port`) per inviare le
notifiche di fine partita via **unicast**. L'IP proviene dal socket TCP (source
address della connessione), la porta dal campo `udpPort` nel messaggio di login.

## Campi
- `endpoints` — `ConcurrentHashMap<Integer,Endpoint>` (thread-safe).
- Inner `Endpoint { final InetAddress address; final int port; }`.

## Metodi
- `register(userId, address, udpPort)` — inserisce solo se `address != null` e
  `0 < udpPort <= 65535`.
- `unregister(userId)` — rimuove l'endpoint (al logout).
- `getEndpoint(userId)` — ritorna `Object[]{address, port}` o `null`.
- `clear()` — svuota il registro (es. allo shutdown).

## Concorrenza
Solo `ConcurrentHashMap`; nessun lock esplicito.

## Collegamenti
- `network/ClientHandler`: popola/rimuove endpoint a login/logout.
- `network/UdpNotifier`: legge gli endpoint per inviare.
- `core/GameManager.logoutUser`: chiama `unregister`.
