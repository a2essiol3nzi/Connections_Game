# `network/GameScheduler` — scheduler della partita attiva

## Ruolo
Unico worker di un `ScheduledExecutorService` che gestisce il ciclo di vita:
il primo task attende la deadline di `current`; i successivi attendono il delay
fisso dalla conclusione del task precedente. Ogni task finalizza gli esiti →
**persist event-driven** (utenti + storico) → ruota alla partita successiva →
invia la notifica UDP ai partecipanti della partita conclusa.

## Campi
- `gameMan` — `GameManager` (current/rotate/finalize/persistHistory).
- `users` — `UserStore` (aggiorna statistiche + `persist()`).
- `notifier` — `UdpNotifier` (notifica fine partita su `Set<Integer>`).
- `durationMs` — delay fisso tra completamento di un task e inizio del successivo.
- `executor` — servizio monothread con worker nominato `scheduler`.

## Flusso (`start` → task periodico)
1. `start()` legge la prima `current` e programma `endCurrentGame()` con
   `initialDelay = current.endTimeMs - now`.
2. `scheduleWithFixedDelay` invoca poi il task dopo `durationMs` dal completamento
   del task precedente.
3. `gameMan.finalizeGame(users)` — registra esiti + statistiche.
4. `users.persistUsers()` — persistenza utenti (stats) event-driven post-finalize;
   gestisce `IOException` con log. (Lo storico `gameMan.persistHistory()` è
   salvato qui insieme.)
5. `parts = g.participants()` — snapshot dei destinatari della partita conclusa.
6. `gameMan.rotate(now)` — nuova partita (auto-join degli `onlineUsers`).
7. `notifier.notifyEnd(parts, {type:"GAME_ENDED", gameId, roundId})`.

## Arresto
`shutdown()` impedisce altre scadenze e attende l'eventuale task in corso senza
interromperlo: la sequenza `finalize → persist → rotate → notify` deve restare
atomica rispetto alla persistenza finale. `ServerMain` lo invoca prima dello
snapshot conclusivo, quindi questo non concorre con `endCurrentGame()`.

> **Semantica UDP**: la notifica è un *segnale* (gameId/roundId), NON contiene
> i risultati. Il client, ricevutala, legge l'esito via TCP
> (`requestGameInfo(roundId)` / `requestGameStats`) attingendo dallo storico
> `GameManager.history`. La rotazione PRECEDE l'invio: il `roundId` notificato
> non coincide più con `current.roundId`, perciò la richiesta seleziona sempre
> lo storico completo senza retry.

## Concorrenza
Un `ScheduledExecutorService` a un solo worker serializza i task: non esistono
due `finalizeGame` concorrenti. Il fixed delay parte al completamento del task,
quindi la porzione successiva a `rotate` (notifica UDP e log) si aggiunge alla
durata osservata della nuova partita. Lo swap di `ActiveGame` in
`GameManager.rotate` è `synchronized`.
`persist()`/`persistHistory()` usano l'`ioLock` dedicato (rispettivamente in
`UserStore`/`GameManager`) ⇒ serializzati tra loro e con l'event-driven di
`register`/`updateCredentials` (nessun timer).

## Collegamenti
- `core/GameManager`: `current`/`finalizeGame`/`rotate`/`persistHistory`.
- `core/ActiveGame`: legge `endTimeMs`, `finalized`, `roundId`, `participants()`.
- `network/UdpNotifier`: invio notifica async (payload con `roundId`).
- `core/UserStore`: `persist()` (stats post-finalize).
