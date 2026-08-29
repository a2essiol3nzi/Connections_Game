# `TC` — TestClient + avvio server su config temporanea

## Ruolo
Wrapper su `client.ClientConn` per parlare al protocollo. Espone metodi
tipizzati per le **9 operazioni** e la riconversione `payload → POJO` (stesso
pattern del client reale: `Gson.fromJson(toJson(payload), Cls)`). Fornisce
anche `startServer()` per avviare il server su config temporanea (porte alte +
file persist/history in `/tmp`) così i test NON toccano `data/users.json` di
produzione.

## Wrapper operazioni (9)
`register(u,p)`, `updateCredentials(oldU,oldP,newU,newP)`, `login(u,p,udpPort)`,
`logout()`, `submit(words)`, `gameInfo(gameId)`, `gameStats(gameId)`,
`leaderboard(playerName, topN)`, `playerStats()`. Tutti costruiscono `Request`
e chiamano `conn.sendAndRetreive`.

## Riconversione payload → POJO
`asGameInfo`, `asStats`, `asLb`, `asPlayer`, `asSubmit` (→
`protocol.payload.*`).

## `startServer(projectDir, gameDurationSec)` — boot server temp
1. Porte TCP/UDP **libere** (`ServerSocket(0)`/`DatagramSocket(0)`).
2. Scrive `server.properties` temp con porte + `games.file` (produzione) e
   `persist`/`history` in `/tmp/conn_test_*`.
3. `ProcessBuilder("java", "-cp", "out:lib/...", "server.core.ServerMain", cfg)`.
4. Attende (≤15s) che il server ascolti sulla porta TCP: bind OK = non ancora
   up, bind fallito = up. Ritorna `Server{proc, tcpPort}`.

Inner `Server` — wrapper con `stop()` che distrugge il processo (`destroy` +
`waitFor`, `destroyForcibly` su interrupt).

## Collegamenti
- `client.ClientConn`: trasporto (via `conn.sendAndRetreive`).
- `protocol.Request`/`Response`, `protocol.payload.*`.
- `server.core.ServerMain`: il processo avviato.
- Usato da `TestFunc`, `TestLoad`, `TestStats`, `RunAll`.