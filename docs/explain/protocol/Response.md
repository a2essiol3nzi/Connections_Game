# `protocol/Response` — envelope di risposta (server → client)

## Ruolo
POJO Gson per serializzare la risposta del server. Formato unico:
- successo → `{"status":"OK", "payload":{...}}`
- errore → `{"status":"ERROR", "errorCode":"ERR_...", "message":"...", "payload":null}`

I dati specifici dell'operazione vanno in `payload` (`JsonObject` arbitrario),
MAI un `errorCode` annidato nel payload.

## Campi
| Campo | Tipo | Presente quando |
|-------|------|-----------------|
| `status` | String | `"OK"` \| `"ERROR"` (sempre) |
| `errorCode` | String | solo se `status == "ERROR"` |
| `message` | String | spiegazione umana (da `Errors.message`), solo su ERROR |
| `payload` | `JsonObject` | dati dell'operazione (o null) |

## Metodi factory
- `ok(JsonObject payload)` → `new Response("OK", null, null, payload)`.
- `err(Errors e)` → `errorCode = e.name()`, `message = e.message` (da enum).
- `err(String code)` → codice stringa grezzo (esiti interni stile `"ERR_*"`,
  `"UNKNOWN_OPERATION"`, `"BAD_REQUEST"`).

## Note
Costruttore vuoto presente per Gson. `ClientHandler`/`GameManager` costruiscono
le `Response` traducendo i `null`/enum ritornati in `ok`/`err`.

## Collegamenti
- `network/ClientHandler`: usa `ok`/`err` nel `dispatch`.
- `protocol/Errors`: sorgente dei codici + messaggi in `err(Errors)`.
- `protocol/Request`: complementare in ingresso.
