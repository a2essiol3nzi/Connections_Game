# `protocol/payload/PlayerStatsPayload` — risposta a requestPlayerStats

## Ruolo
POJO della risposta a `requestPlayerStats`: statistiche personali in stile
NYT.

## Campi
| Campo | Tipo | Note |
|-------|------|------|
| `username` | String | account a cui si riferiscono le stats (dal server, sempre aggiornato) |
| `puzzlesCompleted` | int | vinte + perse + non finite |
| `winRate` | int | percentuale (0-100) |
| `lossRate` | int | percentuale (0-100) |
| `currentStreak` | int | vittorie consecutive |
| `maxStreak` | int | storico massimo |
| `perfectPuzzles` | int | 0 errori |
| `mistakeHistogram` | int[6] | [0..3] vinte con 0..3 err, [4] perse, [5] non finite |

> Il client NON tiene lo username in locale: lo legge da questo campo (il server
> lo mette sempre aggiornato, anche dopo `updateCredentials`/rinomina).

## Collegamenti
- `server/core/GameManager`: build (`playerStats`, snapshot sotto lock).
- `client/Cli`: consumo (`renderPlayerStats`).