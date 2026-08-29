# `TestLoader` — suite robustezza GameLoader (unit)

## Ruolo
Suite **unit** (nessun server) che verifica la progettazione del
`GameLoader` pigro-ciclico: `total()` (conteggio strutturale con `skipValue`),
ordine sequenziale + wrap ciclico, e la **robustezza runtime** che salta le voci
il cui data-binding fallisce senza crash.

## Fixture
- `test/sub-game.json` — 6 partite valide (gameId 0..5).
- I file corrotti sono generati in `/tmp` (sub-game.json resta pulito).

## `run(projectDir)` — sezioni
1. **Integrità + ordine** — `total()==6`; `next()` sequenziale `0..5`; wrap
   ciclico (7° = 0).
2. **Data-binding rotto** (gameId → "NOTANUMBER" in campo centrale) — `total()`
   resta 6 (`skipValue` non fa binding); `next()` salta la voce rotta
   `[0,1,3,4,5,0]` e continua ciclico.
3. **Prima voce rotta** — `[1,2,3,4,5,1]`.
4. **Ultima voce rotta** — `[0,1,2,3,4,0]`.
5. **Gruppo mancante (3/4)** — `[0,1,3,4,5,0,1,3]` (la voce va saltata;
   `total()==6`).
6. **Tema mancante su un gruppo** — `[0,1,2,4,5,0,1,2]`.
7. **Parola null in un gruppo** — `[0,1,2,3,5,0,1,2]` (evita NPE a runtime).

Helper privati: `writeNullWord`, `writeMissingGroup`, `writeMissingTheme`,
`writeCorrupted*` (sostituzione testuale di `gameId`), `readGames`/`writeGames`
(Gson pretty).

## Collegamenti
- `server.loader.GameLoader` + `CyclicGameIterator`; `server.model.GameData`.
- `test/sub-game.json` (fixture).
- `T` (assertion).