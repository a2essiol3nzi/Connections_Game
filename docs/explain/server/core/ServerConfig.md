# `core/ServerConfig` — parametri da file `.properties`

## Ruolo
Carica i parametri del server da un file `.properties` all'avvio (§4: NO CLI,
NO interattivo). I campi sono `public final` e immutabili dopo la lettura.

## Campi (con default)
| Campo | Tipo | Default | Significato |
|-------|------|---------|-------------|
| `tcpPort` | int | `12345` | porta accept TCP |
| `udpPort` | int | `12346` | porta notifiche UDP |
| `gameDurationSec` | int | `600` | durata partita (10 min) |
| `poolSize` | int | `16` | thread pool acceptor |
| `persistIntervalSec` | int | `30` | intervallo snapshot utenti+storico |
| `gamesFile` | String | `data/games.json` | file partite (array JSON) |
| `persistFile` | String | `data/users.json` | file utenti |
| `historyFile` | String | `data/history.json` | file **storico partite** (nuovo) |

## Metodi
- `static ServerConfig load(String path)` — apre il file con `FileReader`,
  popola `Properties`, costruisce l'istanza. Lancia `IOException` (gestita in
  `ServerMain`).

## Concorrenza
Istanza immutabile dopo `load` → nessuna sincronizzazione necessaria; è
condivisa `final` tra tutti i componenti via `Context`.

## Collegamenti
- `core/ServerMain`: unico chiamante di `load`.
- `core/Context`: riceve l'istanza e la propaga a store/manager/notifier/registry.
