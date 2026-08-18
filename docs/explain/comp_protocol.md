# Documentazione componente: `protocol/` (messaggi client↔server)

Pacchetto dedicato ai tipi di scambio messaggi condivisi tra client e server.

## `protocol/Errors` (enum codici errore CENTRALIZZATI)
Tutti gli errori del server passano da qui (§5: "Specificare correttamente
codici di errore"). Invece di stringhe hardcoded sparse, un solo enum con:
- `name()` → codice inviato al client (es. `ERR_USER_NOT_FOUND`);
- `message` → spiegazione umana (debug server / client).
Metodi che ritornano `null` indicano successo ("OK").

Codici: `ERR_INVALID`, `ERR_USER_NOT_FOUND`, `ERR_USERNAME_TAKEN`,
`ERR_WRONG_PASSWORD`, `ERR_NOT_LOGGED_IN`, `ERR_NOT_JOINED`,
`ERR_NO_ACTIVE_GAME`, `ERR_GAME_NOT_FOUND`, `ERR_MALFORMED`,
`ERR_GAME_OVER_FOR_YOU`, `UNKNOWN_OPERATION`.

## `protocol/Request`
Envelope di RICHIESTA: `{"operation":..., ...}`. Campi opzionali (GSON lascia
`null` ciò che manca). `operation` obbligatoria.

## `protocol/Response`
Envelope di RISPOSTA: `{"status":"OK","payload":{...}}` oppure
`{"status":"ERR","errorCode":"ERR_...","payload":null}`.
Helper: `ok(payload)`, `err(Errors)`, `err(String)`.

## Collegamenti
- `network/ClientHandler`: deserializza `Request`, costruisce `Response`.
