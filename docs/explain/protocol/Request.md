# `protocol/Request` — envelope di richiesta (client → server)

## Ruolo
POJO Gson **condiviso** tra client (serializza in `ClientConn.sendAndRetreive`)
e server (`ClientHandler` deserializza) per una riga JSON di richiesta (§5). La
chiave `"operation"` è obbligatoria; tutti gli altri campi sono **opzionali**
(Gson lascia `null` ciò che non è presente).

## Campi
| Campo | Tipo | Usato da |
|-------|------|----------|
| `operation` | String | tutte (register/login/logout/submitProposal/request*) |
| `username`, `psw` | String | register, login |
| `oldUsername`, `oldPsw`, `newUsername`, `newPsw` | String | updateCredentials |
| `udpPort` | Integer | login (**OBBLIGATORIA**: porta UDP su cui il client ascolta le notifiche di fine partita) |
| `words` | `List<String>` | submitProposal (4 parole) |
| `roundId` | Integer | requestGameInfo, requestGameStats (`-1` = partita corrente; altrimenti id round dello storico) |
| `playerName`, `topPlayers` | String, Integer | requestLeaderboard |

## Note
Nessun metodo: contenitore di dati puro. `ClientHandler.dispatch` seleziona
l'azione su `operation` e legge i campi rilevanti. Identificazione lato server
per **userId** (non da qui): il `username` serve solo a login/register/rename.

## Collegamenti
- `network/ClientHandler`: `GSON.fromJson(line, Request.class)`.
- `protocol/Response`: complementare in uscita.
- `protocol/Errors`: codici di errore possibili nelle risposte.
