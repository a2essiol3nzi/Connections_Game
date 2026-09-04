# `TestFunc` — suite funzionale E2E delle operazioni

## Ruolo
Suite **funzionale** che esegue tutte le **9 operazioni** del protocollo su un
server reale, coprendo: codici di errore, gate di auth, la regola
MALFORMATA-vs-ERRATA (§2.2), case-insensitivity, confini payload e lifecycle
credenziali. Usa la prima partita caricata (source gameId 0) con le parole
reali da `data/games.json`.

## Dati
- `Group`/`Game` (static nested) — shape dell'array JSON partite.
- `WORDS_GROUPS`, `G0..G3` — gruppi di parole della partita 0.
- `GAMES` — cache di tutto `games.json` (usata da `TestStats.groupsOf`).
- `loadWords(File)` legge il file.

## `run(port)` — blocchi di test (9 sezioni)
1. **register** — nuovo utente, `ERR_USERNAME_TAKEN`, senza psw, `register` da
   loggato → `ERR_ALREADY_LOGGED_IN`.
2. **login/logout** — senza `udpPort` → `BAD_REQUEST`; psw errata e utente
   inesistente → stesso `ERR_INVALID_CREDENTIALS` (anti-enumerazione); doppio
   login / logout non loggato.
3. **gate auth** — le 5 operazioni protette senza login → `ERR_NOT_LOGGED_IN`.
4. **submit (MALFORMATA vs ERRATA)** — gruppo errato → `WRONG` (-4);
   parola fuori board → `ERR_MALFORMED` (invariato); duplicati →
   `ERR_MALFORMED`; gruppo corretto → `CORRECT` (+6); **case-insensitive**
   (minuscole riconosciute); vittoria a 3; `ERR_GAME_OVER_FOR_YOU` dopo.
5. **info/stats** — `remainingWords` corretto, `gameStats` live.
6. **leaderboard** — top 2, `playerName` inesistente →
   `ERR_PLAYER_NOT_FOUND`.
7. **playerStats** — `puzzlesCompleted>=0`, `mistakeHistogram` lungo 6.
8. **updateCredentials** — rinomina (la sessione NON si sloga), cambio psw,
   login con vecchia psw ora errata → `ERR_INVALID_CREDENTIALS`.
9. **input ostili** — operation vuota/sconosciuta → `UNKNOWN_OPERATION`,
   operation mancante → `BAD_REQUEST`.

Fornisce anche `groupsOf(int gameIndex)` (static, pubblico) — gruppi della
partita `gameIndex` (usato da `TestStats`).

## Collegamenti
- `TC` (client), `T` (assertion), `protocol.payload.GameInfoPayload` ecc.