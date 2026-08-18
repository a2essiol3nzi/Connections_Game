# Documentazione componente: `network/` (comunicazione server)

Pacchetto per la comunicazione socket lato server.

## `network/ConnectionAcceptor`
Accetta connessioni TCP e le smista a thread del pool (thread pooling, §3).
Una connessione per client resta aperta per tutta la sessione (persistente).
Usa `ServerSocket` + `ExecutorService` (fixed). Gestisce internamente gli
`IOException` di rete (niente throws verso il main).

## `network/ClientHandler`
Gestisce UNA connessione client (persistente) sul pool. Protocollo line-based:
ogni messaggio è una riga JSON terminata da `\n`. Deserializza `Request`,
effettua il `dispatch` delle 9 operazioni (con gate `loggedInUser` per quelle
riservate), scrive `Response`. Il logout è implicito alla chiusura del socket.

## `network/UdpNotifier`
Invia notifiche async di fine partita ai partecipanti via UDP (§2.2, §3).
Unicast per partecipante (no multicast: nuovo ordinamento). Invia in loopback
(client sulla stessa macchina).

## `network/GameScheduler`
Thread unico: attende la scadenza della partita corrente (sleep fino a
`endTimeMs`), poi `finalizeGame` → notifica UDP ai partecipanti → `rotate`.
Lo swap di `ActiveGame` avviene in `GameManager.rotate` (synchronized).

## Collegamenti
- `core/Context`: risorse condivise passate agli handler.
- `core/ServerMain`: avvia acceptor/scheduler.
