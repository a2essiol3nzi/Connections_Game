# `protocol/payload/GameInfoPayload` — risposta a requestGameInfo

## Ruolo
POJO della risposta a `requestGameInfo`. Copre sia la partita CORRENTE (live)
sia quella CONCLUSa (storico): i campi non pertinenti restano `null` e vengono
**omessi dal JSON** (Gson non serializza i null), quindi il wire resta
identico a prima ma senza costruire `JsonObject` a mano nei caller.

## Campi (nullable = presenti solo nel ramo pertinente)
| Campo | Tipo | Live | Storico |
|-------|------|------|---------|
| `gameId` | Integer | sì (roundId) | sì |
| `sourceGameId` | Integer | sì | sì |
| `remainingSec` | Integer | sì | — |
| `correct` | Integer | sì | sì |
| `errors` | Integer | sì | sì |
| `score` | Integer | sì | sì |
| `finished` | Boolean | sì | `true` |
| `outcome` | String | — | sì (`WON`/`LOST`/`NOT_FINISHED`) |
| `remainingWords` | List<String> | sì | — |
| `assignment` | List<GroupPayload> | — | sì |

## Note
- Lato server costruito da `GameManager.gameInfo`.
- Lato client deserializzato da `Cli.renderGameInfo`/`UdpClient` (cast via Gson).
- Un unico POJO con nullable copre il vari-shape della §2.1 (live vs storico).

## Collegamenti
- `protocol/payload/GroupPayload`: elementi di `assignment`.
- `server/core/GameManager`: build; `client/Cli`, `client/UdpClient`: consumo.