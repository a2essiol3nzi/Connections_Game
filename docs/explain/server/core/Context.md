# `core/Context` — contenitore delle risorse condivise

## Ruolo
Evita oggetti globali: un'unica istanza creata in `ServerMain` e passata per
referenza a handler, scheduler e thread di persistenza. Raggruppa i componenti
"pesanti" del server.

## Campi (tutti `public final`)
| Campo | Tipo | Inizializzato in |
|-------|------|------------------|
| `cfg` | `ServerConfig` | passato dal costruttore |
| `users` | `UserStore` | `new UserStore(cfg.persistFile)` |
| `games` | `GameManager` | `new GameManager(loader, cfg.gameDurationSec, cfg.historyFile)` |
| `udpRegistry` | `UdpRegistry` | `new UdpRegistry()` |
| `notifier` | `UdpNotifier` | `new UdpNotifier(udpRegistry)` |

## Metodi
- `Context(ServerConfig cfg, GameLoader loader)` — costruttore; crea store,
  manager (con `historyFile`), `udpRegistry` e `notifier` (che riceve il
  registry). Il `GameLoader` è passato (non creato qui) perché è già costruito e
  verificato in `ServerMain`.

## Concorrenza
Oggetto immutabile dopo costruzione. I singoli componenti gestiscono la propria
sincronizzazione.

## Collegamenti
- `core/ServerMain`: l'unico che lo costruisce.
- `network/ClientHandler`, `network/GameScheduler`: lo ricevono per accedere a
  store/manager/notifier/udpRegistry. (Nessun `PersistenceThread`: persistenza
  event-driven.)
- `network/UdpRegistry`, `network/UdpNotifier`: creati qui.
