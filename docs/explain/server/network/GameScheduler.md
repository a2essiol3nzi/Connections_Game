# `network/GameScheduler` — scheduler della partita attiva

## Ruolo
Unico thread di servizio che gestisce il ciclo di vita della partita: attende
la scadenza di `current` → finalizza gli esiti → **persist event-driven** (utenti
+ storico) → ruota alla partita successiva → invia la notifica UDP ai partecipanti
della partita conclusa.

## Campi
- `gameMan` — `GameManager` (current/rotate/finalize/persistHistory).
- `users` — `UserStore` (aggiorna statistiche + `persist()`).
- `notifier` — `UdpNotifier` (notifica fine partita su `Set<Integer>`).

## Flusso (`run`, ciclo infinito)
1. `g = gameMan.current()`; se null → `sleep(1000)`, riprova.
2. `waitMs = g.endTimeMs - now`; se > 0 → `sleep(waitMs)`.
3. `gameMan.finalizeGame(users)` — registra esiti + statistiche.
4. `users.persistUsers()` — persistenza utenti (stats) event-driven post-finalize;
   gestisce `IOException` con log. (Lo storico `gameMan.persistHistory()` è
   salvato qui insieme.)
5. `parts = g.participants()` — snapshot dei destinatari della partita conclusa.
6. `gameMan.rotate(now)` — nuova partita (auto-join degli `onlineUsers`).
7. `notifier.notifyEnd(parts, {type:"GAME_ENDED", gameId, roundId})`.

> **Semantica UDP**: la notifica è un *segnale* (gameId/roundId), NON contiene
> i risultati. Il client, ricevutala, legge l'esito via TCP
> (`requestGameInfo(roundId)` / `requestGameStats`) attingendo dallo storico
> `GameManager.history`. La rotazione PRECEDE l'invio: il `roundId` notificato
> non coincide più con `current.roundId`, perciò la richiesta seleziona sempre
> lo storico completo senza retry.

## Concorrenza
Coordinazione via `Thread.sleep` fino a `endTimeMs`; `sleep()` ricalcola il
deadline ed effettua **retry** su `InterruptedException` (non esce dal ciclo).
Lo swap di `ActiveGame` in `GameManager.rotate` è `synchronized`.
`persist()`/`persistHistory()` usano l'`ioLock` dedicato (rispettivamente in
`UserStore`/`GameManager`) ⇒ serializzati tra loro e con l'event-driven di
`register`/`updateCredentials` (nessun timer).

## Collegamenti
- `core/GameManager`: `current`/`finalizeGame`/`rotate`/`persistHistory`.
- `core/ActiveGame`: legge `endTimeMs`, `finalized`, `roundId`, `participants()`.
- `network/UdpNotifier`: invio notifica async (payload con `roundId`).
- `core/UserStore`: `persist()` (stats post-finalize).
