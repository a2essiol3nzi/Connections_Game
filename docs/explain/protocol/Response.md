# `protocol/Response` — envelope di risposta (server → client)

## Ruolo
POJO Gson per serializzare la risposta del server. Formato unico:
- successo → `{"status":"OK", "payload":{...}}`
- errore → `{"status":"ERROR", "errorCode":"ERR_...", "message":"...", "payload":null}`

`payload` è l'oggetto specifico dell'operazione (un POJO di
`protocol.payload`), serializzato da Gson: **il tipo è determinato
dall'operazione** della richiesta. MAI un `errorCode` annidato nel payload.

## Campi
| Campo | Tipo | Presente quando |
|-------|------|-----------------|
| `status` | String | `"OK"` \| `"ERROR"` (sempre) |
| `errorCode` | String | solo se `status == "ERROR"` |
| `message` | String | spiegazione umana (da `Errors.message`), solo su ERROR |
| `payload` | **Object** | payload dell'operazione (un POJO di `protocol.payload`, o null) |

## Metodi factory
- `ok(Object payload)` → `new Response("OK", null, null, payload)`. Accetta un
  qualunque POJO di `protocol.payload` (o `null` per register/update/logout).
- `err(Errors e)` → `errorCode = e.name()`, `message = e.message` (da enum).
- `err(String code)` → codice stringa grezzo (esiti interni stile `"ERR_*"`).

## Note
- Costruttore vuoto presente per Gson.
- `ClientHandler`/`GameManager` costruiscono le `Response` passando a `ok(...)`
  il `*Payload` ritornato dai metodi di `GameManager` o creato direttamente.
- Lato client il payload (letto come `Object`) viene riconvertito al POJO
  dell'operazione via `GSON.fromJson(GSON.toJson(res.payload), Classe.class)`.

## Collegamenti
- `network/ClientHandler`: usa `ok`/`err` nel `dispatch`.
- `protocol/payload/*` (`GameInfoPayload`, `GameStatsPayload`,
  `LeaderboardPayload`, `PlayerStatsPayload`, `SubmitPayload`): tipi del payload.
- `protocol/Errors`: sorgente dei codici + messaggi in `err(Errors)`.
- `protocol/Request`: complementare in ingresso.