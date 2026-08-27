# `protocol/payload/GameStatsPayload` — risposta a requestGameStats

## Ruolo
POJO della risposta a `requestGameStats`.

## Campi
| Campo | Tipo | Live | Storico |
|-------|------|------|---------|
| `participantsTotal` | Integer | sì | sì |
| `inProgress` | Integer | sì | — |
| `finished` | Integer | sì | sì |
| `won` | Integer | sì | sì |
| `remainingSec` | Integer | sì | — |
| `avgScore` | Integer | — | sì |

Campi non pertinenti = `null` ⇒ omessi dal JSON.

## Collegamenti
- `server/core/GameManager`: build (ramo live vs storico).
- `client/Cli`: consumo (`renderGameStats`).