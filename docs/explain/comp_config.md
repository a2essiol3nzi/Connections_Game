# Documentazione componente: `server.properties` + `ServerConfig`

## File: `server.properties` (configurazione server)
File di configurazione letto all'avvio (§4: i parametri NON devono essere
passati da linea di comando né chiesti interattivamente). Un path alternativo
può essere passato come primo argomento a `ServerMain`.

| Chiave | Default | Significato |
|--------|---------|-------------|
| `tcp.port` | 12345 | Porta TCP su cui il server accetta i client |
| `udp.port` | 12346 | Porta UDP su cui il server invia le notifiche async di fine partita |
| `game.duration.sec` | 600 | Durata di ogni partita, in secondi |
| `games.file` | data/games.json | Path del file JSON delle partite (fornito dai docenti) |
| `persist.file` | data/users.json | Path del file JSON degli utenti (persistenza) |
| `persist.interval.sec` | 30 | Intervallo di salvataggio periodico degli utenti |
| `pool.size` | 16 | Numero di thread nel pool per le connessioni client |

## Classe: `ServerConfig`
Carica il `.properties` in campi `public final` tipizzati (`int`, `String`).
Metodo statico `load(String path)` → `ServerConfig`. Letto una volta all'avvio
da `ServerMain`; i valori sono poi immutabili per tutta la vita del server.

**Uso nel flusso:** `ServerMain` → `ServerConfig.load(...)` → costruisce
`GameLoader`, `Context`, `GameScheduler`, `PersistenceThread`, `ConnectionAcceptor`.
