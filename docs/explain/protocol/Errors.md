# `protocol/Errors` — codici di errore centralizzati

## Ruolo
Enum che centralizza i codici di errore (§5), **condiviso tra server e client**
(import `protocol.Errors`). Niente stringhe hardcoded. `name()` = codice inviato
al client (es. `"ERR_USER_NOT_FOUND"`); `message` = spiegazione umana (in cima
al JSON di errore).

## Codici definiti
| Codice | Messaggio |
|--------|-----------|
| `BAD_REQUEST` | Richiesta non valida (operation mancante o JSON illeggibile) |
| `ERR_INVALID` | Richiesta non valida (campi mancanti/vuoti) |
| `ERR_USER_NOT_FOUND` | Utente inesistente |
| `ERR_INVALID_CREDENTIALS` | Credenziali non valide — login con utente inesistente o psw errata, e update con vecchia psw errata |
| `ERR_USERNAME_TAKEN` | Username già registrato |
| `ERR_NOT_LOGGED_IN` | Operazione richiesta ma utente non loggato |
| `ERR_ALREADY_LOGGED_IN` | Utente già loggato (altra sessione) |
| `ERR_NOT_JOINED` | Utente non partecipa alla partita corrente |
| `ERR_NO_ACTIVE_GAME` | Nessuna partita attiva al momento |
| `ERR_GAME_NOT_FOUND` | Partita inesistente |
| `ERR_MALFORMED` | Proposta malformata (parole non valide o già usate) |
| `ERR_GAME_OVER_FOR_YOU` | Hai già terminato questa partita |
| `ERR_PLAYER_NOT_FOUND` | Giocatore inesistente |
| `UNKNOWN_OPERATION` | Operazione sconosciuta |

## Convenzione
Un metodo che ritorna `null` indica successo ("OK"); un valore `Errors` (o
codice stringa `"ERR_*"`) indica errore. `ActiveGame.JoinResult`/`SubmitResult`
integrano direttamente `Errors` (`error()`), così `ClientHandler` mappa 1:1 senza
letterali. `ERR_ALREADY_LOGGED_IN` è **presente** (login su id già online
rifiutato).

**Anti-enumerazione nel login**: `login` restituisce sempre
`ERR_INVALID_CREDENTIALS` sia per utente inesistente sia per password errata,
così non rivela l'esistenza di un account. `updateCredentials` mantiene invece
`ERR_USER_NOT_FOUND` per `oldUsername` inesistente e usa
`ERR_INVALID_CREDENTIALS` solo per `oldPsw` errata.

## Collegamenti
- `protocol/Response`: `Response.err(Errors)` usa `name()` + `message`.
- Tutti i componenti server: ritornano questi codici come `Errors`/stringa.
