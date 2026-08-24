# Protocollo e messaggi (§5)

Tutti i messaggi sono **stringhe JSON**. Envelope risposta (formato adottato):
`{"status":"OK", "payload":{...}}` oppure `{"status":"ERROR", "errorCode":"ERR_...", "message":"...", "payload":null}`.
Lato server l'identificazione è per **userId immutabile** (non username).

## Richieste (client → server)
| Op | Campi obbligatori | Note |
|----|-------------------|------|
| register | `operation`, `username`, `psw` | TCP (nuovo ordinamento) |
| updateCredentials | `operation`, `oldUsername`, `oldPsw`, + `newUsername`\|`newPsw` (≥1) | aggiorna nome e/o psw |
| login | `operation`, `username`, `psw`, **`udpPort` (1..65535)** | auto-join + registra endpoint UDP (IP dal socket TCP) |
| logout | `operation` | rimuove userId dal registry online + UDP |
| submitProposal | `operation`, `words:[4 STRING]` | 4 parole distinte (valutate per `userId`) |
| requestGameInfo | `operation`, `gameId:INT` | `roundId = -1` = corrente; altro = storico (espone `assignment`+tema) |
| requestGameStats | `operation`, `gameId:INT` | `roundId = -1` = corrente |
| requestLeaderboard | `operation`, + `playerName`\|`topPlayers:INT` | `topPlayers` assente = tutti; rango risolto per id |
| requestPlayerStats | `operation` | per `userId` loggato |

## Codici errore (enum `protocol/Errors`, centralizzato)
- `BAD_REQUEST` — JSON illeggibile / operation mancante / `udpPort` assente o invalido
- register: `ERR_USERNAME_TAKEN`, `ERR_INVALID`
- updateCredentials: `ERR_WRONG_PASSWORD`, `ERR_USERNAME_TAKEN`, `ERR_USER_NOT_FOUND`
- login: `ERR_WRONG_PASSWORD`, `ERR_USER_NOT_FOUND`, `ERR_ALREADY_LOGGED_IN`
- logout: `ERR_NOT_LOGGED_IN`
- submitProposal: `ERR_NOT_LOGGED_IN`, `ERR_NO_ACTIVE_GAME`, `ERR_GAME_OVER_FOR_YOU`,
  `ERR_MALFORMED` (parola non nel gioco / già assegnata / ≠4 distinte), `ERR_NOT_JOINED`
- requestGameInfo/Stats: `ERR_GAME_NOT_FOUND`, `ERR_NO_ACTIVE_GAME`
- requestLeaderboard: `ERR_PLAYER_NOT_FOUND`
- requestPlayerStats: `ERR_NOT_LOGGED_IN`, `ERR_USER_NOT_FOUND`
- `UNKNOWN_OPERATION`

> `ERR_ALREADY_LOGGED_IN` è il codice per login duplicato; `ERR_NOT_LOGGED_IN` resta per chiamate protette senza autenticazione.

## Regola MALFORMATA vs ERRATA (§2.2 — fondamentale)
- **Errata** = 4 parole valide del gioco che NON formano un gruppo corretto
  → conta come errore, **-4**, incrementa contatore errori.
- **Malformata** = parole già assegnate correttamente a un gruppo, o una/più
  parole NON parte della partita, o ≠4/distinte
  → notificata ma **NON cambia stato** (no penalità, no incremento errori).

## Logica di punteggio (§1)
`score = 6 * corrette - 4 * errate`, `corrette ∈ {0,1,2,3}` (3=win), `errate ∈ {0..4}` (4=loss).
- win con 3 errori → 18-12 = **+6**; loss → max **-16**.

## Schema JSON partite (CONFERMATO)
Array top-level di 911 oggetti: `[{"gameId":int,"groups":[{"theme":str,"words":[4]}]}]`.
`theme` nascosto al client live; finisce nello **storico** (`groupInfo`/`GameHistory.assignment`).
Caricamento pigro via `GameLoader` (`JsonReader`+`skipValue`, O(1)).

## Storico partite (persistito, novità)
`GameManager.history` chiave = `roundId` (monotono); `GameHistory = {roundId, sourceGameId,
GameData.Group[4] (tema+parole), entries: Map<userId, {correct,errors,score,outcome}>}`.
Persistito in `data/history.json` (atomico, cap 10_000 round). `requestGameInfo(roundId!=-1)`
lo espone includendo il `theme` (a fine partita è lecito). `finalizeGame` è **idempotente**
(`compareAndSet` su `finalized`): chiamato da scheduler + shutdown hook + timer, conta 1 sola volta.

## Notifiche async UDP (§2.2, §3)
Al termine partita: `GameScheduler` invia a ogni partecipante (unicast, endpoint dal
`UdpRegistry`) `{type:"GAME_ENDED", gameId, roundId}`. Client deve avere thread UDP in
ascolto (C2) concorrente al NIO TCP.
