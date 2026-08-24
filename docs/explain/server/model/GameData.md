# `model/GameData` — POJO del file partite (schema JSON)

## Ruolo
Classe POJO usata da Gson per (de)serializzare **un oggetto-partita** letto dal
`GameLoader`. Shape confermato: array top-level di oggetti
`{ "gameId": int, "groups": [ { "theme": str, "words": [4] } ×4 ] }`.

## Campi
- `int gameId` — id partita sorgente.
- `List<Group> groups` — i 4 gruppi.
- Inner `Group { String theme; List<String> words; }` — **riusata** anche da
  `ActiveGame.groupInfo()` per lo storico (DTO condiviso).

## Note
Il campo `theme` (categoria nascosta) **non viene mai inviato al client** live:
resta lato server per la valutazione in `ActiveGame` e per lo storico
(`groupInfo()`/`GameHistory`). Gson popola i campi pubblici direttamente.

## Collegamenti
- `loader/GameLoader`: produce `GameData` da `next()`.
- `core/ActiveGame`: costruito da un `GameData` (estrae parole, nasconde theme).
