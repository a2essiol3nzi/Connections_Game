# `protocol/payload/SubmitPayload` — risposta a submitProposal

## Ruolo
POJO della risposta a `submitProposal`: l'esito della proposta
(`CORRECT`/`WRONG`) più lo stato aggiornato del richiedente.

## Campi
| Campo | Tipo | Note |
|-------|------|------|
| `result` | String | `"CORRECT"` \| `"WRONG"` |
| `game` | GameInfoPayload | stato della partita del giocatore dopo la submit |

## Collegamenti
- `server/network/ClientHandler.handleProposal`: build.
- `client/Cli`: deserializza per render + trigger auto-`requestGameStats` a fine partita.
- `protocol/payload/GameInfoPayload`: `game`.