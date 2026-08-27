# `protocol/payload/GroupPayload` — gruppo della soluzione

## Ruolo
Singolo gruppo della soluzione di una partita CONCLUSa: il tema (nascosto) e le
4 parole che lo compongono. Elemento di `GameInfoPayload.assignment`.

## Campi
| Campo | Tipo | Note |
|-------|------|------|
| `theme` | String | categoria nascosta (mai inviata live) |
| `words` | List<String> | le 4 parole del gruppo |

## Collegamenti
- `protocol/payload/GameInfoPayload`: container (`assignment`).
- `server/model/GameData.Group`: fonte (stessa forma, riusata nello storico).