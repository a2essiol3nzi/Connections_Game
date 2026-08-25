# `network/GameScheduler` — scheduler della partita attiva

## Ruolo
Unico thread di servizio che gestisce il ciclo di vita della partita: attende
la scadenza di `current` → finalizza gli esiti → **persist event-driven** (utenti
+ storico) → invia notifica UDP ai partecipanti → ruota alla partita successiva.

## Campi
- `gameMan` — `GameManager` (current/rotate/finalize/persistHistory).
- `users` — `UserStore` (aggiorna statistiche + `persist()`).
- `notifier` — `UdpNotifier` (notifica fine partita su `Set<Integer>`).

## Flusso (`run`, ciclo infinito)
1. `g = gameMan.current()`; se null → `sleep(1000)`, riprova.
2. `waitMs = g.endTimeMs - now`; se > 0 → `sleep(waitMs)`.
3. `gameMan.finalizeGame(users)` — registra esiti + statistiche.
4. `users.persist()` + `gameMan.persistHistory()` — persistenza event-driven (non
   attende il timer del `PersistenceThread`); gestisce `IOException` con log.
5. `notifier.notifyEnd(g.participants(), {type:"GAME_ENDED", gameId, roundId})`.
6. `gameMan.rotate(now)` — nuova partita (auto-join degli `onlineUsers`).

> **Semantica UDP**: la notifica è un *segnale* (gameId/roundId), NON contiene
> i risultati. Il client, ricevutala, legge l'esito via TCP
> (`requestGameInfo(roundId)` / `requestGameStats`) attingendo dallo storico
> `GameManager.history`. Se la notifica arrivasse prima di `finalizeGame` si
> aprirebbe una finestra TOCTOU (esito non ancora scritto) ⇒ il client deve
> attendere/ritentare la lettura storico.

## Concorrenza
Coordinazione via `Thread.sleep` fino a `endTimeMs`; `sleep()` ricalcola il
deadline ed effettua **retry** su `InterruptedException` (non esce dal ciclo).
Lo swap di `ActiveGame` in `GameManager.rotate` è `synchronized`.
`persist()`/`persistHistory()` sono `synchronized` lato store/manager ⇒
serializzati con timer e shutdown hook.

## Collegamenti
- `core/GameManager`: `current`/`finalizeGame`/`rotate`/`persistHistory`.
- `core/ActiveGame`: legge `endTimeMs`, `finalized`, `roundId`, `participants()`.
- `network/UdpNotifier`: invio notifica async (payload con `roundId`).
- `core/UserStore` + `persistence/PersistenceThread`: `persist()`.
