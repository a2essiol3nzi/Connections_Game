# `persistence/PersistenceThread` — snapshot periodico (utenti + storico)

## Ruolo
Thread di servizio che salva su JSON a intervalli regolari (§2.2): ora persiste
**sia** `UserStore` **sia** lo storico partite `GameManager` (non solo utenti).

## Campi
- `users` — `UserStore` (`persist()`).
- `games` — `GameManager` (`persistHistory()`).
- `intervalSec` — periodo (da `ServerConfig.persistIntervalSec`).

## Flusso (`run`, ciclo infinito)
`Thread.sleep(intervalSec*1000)` → `users.persist()` + `games.persistHistory()`
→ log `"[Persist] users + history saved"`. `InterruptedException` ⇒ `interrupt()`
+ `break`; altra `Exception` ⇒ log e continua.

## Concorrenza
Thread dedicato. `persist()`/`persistHistory()` sono `synchronized` (store/
manager) ⇒ scrittura serializzata con `GameScheduler` e shutdown hook.

## Collegamenti
- `core/UserStore` + `core/GameManager`: gli unici chiamati.
- `core/ServerMain`: crea e avvia il thread.
- `core/ServerConfig`: `persistIntervalSec`.
