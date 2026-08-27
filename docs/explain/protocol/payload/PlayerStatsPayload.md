# `protocol/payload/PlayerStatsPayload` — risposta a requestPlayerStats

## Ruolo
POJO della risposta a `requestPlayerStats`: statistiche personali in stile
NYT.

## Campi
| Camp | Tipo | Note |
|------|------|------|
| `puzzlesCompleted` | int | vinte + perse + non finite |
| `winRate` | int | percentuale (0-100) |
| `lossRate` | int | percentuale (0-100) |
| `currentStreak` | int | vittorie consecutive |
| `maxStreak` | int | storico massimo |
| `perfectPuzzles` | int | 0 errori |
| `mistakeHistogram` | int[6] | [0..3] vinte con 0..3 err, [4] perse, [5] non finite |

## Collegamenti
- `server/core/GameManager`: build (`playerStats`, snapshot sotto lock).
- `client/Cli`: consumo (`renderPlayerStats`).