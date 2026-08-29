# `TestStats` — suite sub-logic statistiche su game reali

## Ruolo
Verifica la **sotto-logica delle statistiche personali** (`playerStats` /
`finalizeGame` in `GameManager`/`UserStore`) che scatta solo alla FINE di una
partita reale (rotazione round). Gioca partite reali su un **server dedicato a
durata breve** e ispeziona lo stato dopo ogni round.

## Scenario (3 round)
- **Round 1 (games[0]) — WIN pulita**: 3 gruppi corretti, 0 errori →
  `currentStreak=1`, `maxStreak=1`, `winRate=100`, `perfectPuzzles=1`,
  `hist[0]++`.
- **Round 2 (games[1]) — LOSS**: 4 errori → `currentStreak=0` (reset),
  `maxStreak` resta 1, `winRate=50`, `lossRate=50`, `hist[4]++`.
- **Round 3 (games[2]) — NOT_FINISHED**: nessuna mossa → `puzzlesCompleted=3`,
  `currentStreak=0`, `winRate=33`, `lossRate=33`, `hist[5]++`.

Verifica anche la **penalità**: cumulativo scala `+6`/gruppo, `-4`/errore.

## Metodi
- `run(projectDir)` — avvia server dedicato (`TC.startServer(..., 3)` sec),
  gioca i 3 round, valida le stat, stop in `finally`.
- `awaitPuzzleCount(TC, n)` — poll finché `puzzlesCompleted>=n`.
- `awaitRoundAdvance(TC, prevRoundId)` — attende che la partita corrente sia
  avanzata oltre `prevRoundId` E `finished=false` (necessario: `finalizeGame`
  avanza `puzzlesCompleted` PRIMA di `rotate`, senza questo sync i submit del
  round successivo cadrebbero sul round già finalizzato).

## Collegamenti
- `TC`: client; `TestFunc.groupsOf(i)`: gruppi dei game.
- `protocol.payload.PlayerStatsPayload`/`GameInfoPayload`.
- `core/GameManager` / `core/UserStore`: logica testata.