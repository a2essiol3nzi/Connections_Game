# `network/GameScheduler` — scheduler della partita attiva

## Ruolo
Unico thread di servizio che gestisce il ciclo di vita della partita: attende
la scadenza di `current` → finalizza gli esiti → **persist event-driven** →
invia notifica UDP ai partecipanti → ruota alla partita successiva.

## Campi
- `gm` — `GameManager` (current/rotate/finalize).
- `users` — `UserStore` (aggiorna statistiche in `finalizeGame` e persist).
- `notifier` — `UdpNotifier` (notifica fine partita).

## Flusso (`run`, ciclo infinito)
1. `g = gm.current()`; se null → `sleep(1000)`, riprova.
2. `waitMs = g.endTimeMs - now`; se > 0 → `sleep(waitMs)`.
3. `gm.finalizeGame(users)` — registra esiti + statistiche.
4. `users.persist()` — **persistenza event-driven**: le statistiche finiscono
   su disco subito, senza aspettare il timer del `PersistenceThread` (gestisce
   IOException con log).
5. `notifier.notifyEnd(g.participants(), {type:"GAME_ENDED", gameId})`.
6. `gm.rotate(now)` — nuova partita.

## Concorrenza
Coordinazione via `Thread.sleep` fino a `endTimeMs` (nessun wait/notify). Lo
swap di `ActiveGame` avviene in `GameManager.rotate` (`synchronized`).
`persist()` è `synchronized` lato `UserStore`, quindi l'accesso concorrente
con lo shutdown hook / `PersistenceThread` è serializzato.

## Collegamenti
- `core/GameManager`: `current`/`finalizeGame`/`rotate`.
- `core/ActiveGame`: legge `endTimeMs` e `participants()`.
- `network/UdpNotifier`: invio notifica async.
- `persistence/UserStore`: statistiche in `finalizeGame` + `persist()`.
