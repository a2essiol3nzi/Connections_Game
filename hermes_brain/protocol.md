# Protocollo e messaggi (§5)

Tutti i messaggi sono **stringhe JSON**. Chiavi obbligatorie esatte come in §5.
Convenzione risposta (da definire, §5 lascia aperto il formato):
`{"status":"OK", ...dati...}` oppure `{"status":"ERROR","errorCode":"...","message":"..."}`.

## Richieste (client → server)
| Op | Campi obbligatori | Note |
|----|-------------------|------|
| register | `operation`, `username`, `psw` | TCP (nuovo ordinamento) |
| updateCredentials | `operation`, `oldUsername`, `oldPsw`, + `newUsername`\|`newPsw` (≥1) | aggiorna nome e/o psw |
| login | `operation`, `username`, `psw` | auto-join partita corrente |
| logout | `operation` | |
| submitProposal | `operation`, `words:[4 STRING]` | 4 parole distinte |
| requestGameInfo | `operation`, `gameId:INT` | sentinel (es. -1) = corrente |
| requestGameStats | `operation`, `gameId:INT` | sentinel = corrente |
| requestLeaderboard | `operation`, + `playerName`\|`topPlayers:INT` | `topPlayers` assente = tutti |
| requestPlayerStats | `operation` | |

## Codici errore proposti (da finalizzare)
- register: `ERR_USERNAME_TAKEN`
- updateCredentials: `ERR_WRONG_PASSWORD`, `ERR_USERNAME_TAKEN`, `ERR_USER_NOT_FOUND`
- login: `ERR_WRONG_PASSWORD`, `ERR_USER_NOT_FOUND`, `ERR_ALREADY_LOGGED_IN`
- logout: `ERR_NOT_LOGGED_IN`
- submitProposal: `ERR_NOT_LOGGED_IN`, `ERR_NO_ACTIVE_GAME`, `ERR_GAME_OVER_FOR_YOU`,
  `ERR_MALFORMED` (parola non nel gioco / già assegnata correttamente / ≠4 distinte),
  `ERR_WRONG_GROUP` (conta come errore, -4)
- requestGameInfo/Stats: `ERR_GAME_NOT_FOUND`
- requestLeaderboard: `ERR_PLAYER_NOT_FOUND`
- requestPlayerStats: `ERR_NOT_LOGGED_IN`

## Regola MALFORMATA vs ERRATA (§2.2 — fondamentale)
- **Errata** = 4 parole valide del gioco che NON formano un gruppo corretto
  → conta come errore, **-4**, incrementa contatore errori.
- **Malformata** = parole già assegnate correttamente a un gruppo, o una/more
  parole NON parte della partita, o ≠4/distinte
  → notificata come errore al giocatore ma **NON cambia stato** (no penalità,
  no incremento errori). Stesso trattamento se propone di nuovo un gruppo già
  trovato (già assegnato correttamente).

## Logica di punteggio (§1)
`score = 6 * corrette - 4 * errate`, con `corrette ∈ {0,1,2,3}` (3=win, 4° implicito),
`errate ∈ {0..4}` (4=loss).
- 0 proposte → 0.
- Esempi spec: (1 err,1 corr,1 err,timeout) → 6-8 = **-2** ✓
- win con 3 errori → 18-12 = **+6** ✓; loss → max **-16** ✓

## Schema JSON partite (CONFERMATO dall'utente — vedi status S2)
File ~620KiB, **array JSON top-level** di 911 oggetti, tutti dello stesso shape:
```json
[
  {
    "gameId": 0,
    "groups": [
      {"theme": "WET WEATHER",  "words": ["SNOW","HAIL","RAIN","SLEET"]},
      {"theme": "NBA TEAMS",    "words": ["HEAT","BUCKS","JAZZ","NETS"]},
      {"theme": "KEYBOARD KEYS","words": ["SHIFT","TAB","RETURN","OPTION"]},
      {"theme": "PALINDROMES",  "words": ["LEVEL","KAYAK","RACECAR","MOM"]}
    ]
  }
]
```
- **Array top-level** (NON `{games:[...]}`).
- `gameId`: int (0..910) → id partita.
- `groups`: 4 elementi, ognuno `theme` (stringa categoria) + `words` (4 stringhe).
- Server invia le 16 parole **mescolate**; `groups`/`theme` restano lato server.
- Caricamento: **una volta all'avvio** in memoria. # ponytail: load once;
  qui 620KiB/911 partite è trascurabile; per file realmente enorme → streaming.

## Notifiche async UDP (§2.2, §3)
Al termine partita (timeout globale o chiusura per tutti): il server invia a
ogni partecipante, via UDP, la **classifica** + **statistiche** di quella
partita (equivalente a requestGameInfo/Stats conclusa). Client deve avere
thread UDP in ascolto (C3) concorrente al NIO TCP.
