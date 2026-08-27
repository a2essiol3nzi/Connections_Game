# `protocol/payload/LeaderboardPayload` — risposta a requestLeaderboard

## Ruolo
POJO della risposta a `requestLeaderboard`: la tabella dei giocatori per
punteggio cumulativo e (opzionale) il rango di un utente richiesto via
`playerName`.

## Campi
| Camp | Tipo | Note |
|------|------|------|
| `leaderboard` | List<Row> | classifiche ordinate (desc per score) |
| `playerRank` | Integer | solo se richiesto via `playerName` |

### Row (classe annidata)
| Camp | Tipo |
|------|------|
| `username` | String |
| `cumulativeScore` | int |

## Collegamenti
- `server/core/GameManager`: build (`leaderboard`).
- `client/Cli`: consumo (`renderLeaderboard`).