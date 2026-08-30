# Connections — Second Brain

Progetto fine corso Reti/Lab III (A.A. 2025/26), versione specifica 1.1.
Gioco "Connections" (NYT) implementato in **Java** con architettura **client-server**.

> Stato repo: **server + client completi e verificati**. Package `src/client/` (C1–C4)
> implementati e testati end-to-end. Vedi `status.md` per la board. Il file JSON delle 911 partite
> (~620KiB) è fornito dai docenti; in consegna va in `data/games.json`.

## Stack e vincoli tecnologici (da §3, ordinamento NUOVO)
- Java, compilazione con `javac` (no IDE files in consegna).
- Client: **CLI** (GUI facoltativa, non valutata).
- **Registrazione via TCP** (no RMI — vecchio ordinamento).
- Comunicazione client↔server su **TCP persistente**, messaggi **JSON** (§5, line-based `\n`).
- Client deve usare **NIO** per la connessione TCP; server I/O bloccante + thread pool (ammesso).
- Strutture dati server **sincronizzate**: solo `synchronized` / `wait()`/`notifyAll()` /
  `java.util.concurrent.atomic.*`. **VIETATI** `java.util.concurrent.locks.*`.
- Notifiche async fine partita via **UDP** (unicast, no multicast).
- Persistenza (utenti + **storico partite**) in file **JSON** atomici.
- Consegna: JAR eseguibile client + JAR eseguibile server, `server.properties`/`client.properties`,
  PDF relazione ≤5 pag, allegare `lib/gson-2.11.0.jar`.

## Build & dipendenze (stato attuale)
- **GSON 2.11.0** in `lib/gson-2.11.0.jar` (jar progetto-locale, pronto per la consegna).
- Compilazione: `javac -cp lib/gson-2.11.0.jar -d out $(find src -name '*.java')`
- Esecuzione: `java -cp out:lib/gson-2.11.0.jar server.core.ServerMain`
- JAR: manifest `Main-Class` in `server.jar`/`client.jar`; allegare `lib/gson-2.11.0.jar`.
- `build.sh` (nuovo) presente alla radice per compilare/eseguire.

## Package server (mappa)
- `core/`: `ServerMain` (entry), `ServerConfig` (`.properties`), `Context` (risorse),
  `GameManager` (logica+storico), `ActiveGame`+`PlayerState` (partita), `UserStore` (identità).
- `loader/`: `GameLoader` (streaming pigro, `CyclicGameIterator`).
- `model/`: `GameData` (POJO partite).
- `network/`: `ConnectionAcceptor`, `ClientHandler`, `GameScheduler`, `UdpNotifier`, `UdpRegistry`.
- persistenza utenti/storico: **event-driven** (in `UserStore` e `GameManager`), NESSUNA classe `persistence/` — il vecchio `PersistenceThread` è stato rimosso.

## Package condiviso (server + client)
- `protocol/` (top-level in `src/`, non sotto `server/`): `Request`, `Response`,
  `Errors` (envelope + codici), `GameEnded` (POJO notifica UDP). Il client non
  dipende più da un package col nome "server" per i messaggi.
- `protocol/payload/` (REFACTOR, commit `6ed47c1`): **un POJO per ogni payload
  di risposta** (`GameInfoPayload`, `GameStatsPayload`, `LeaderboardPayload`,
  `PlayerStatsPayload`, `SubmitPayload`, `GroupPayload`), al posto di
  `JsonObject` costruiti/spostati a mano. `Response.payload` è `Object`; il
  tipo è deciso dall'operazione. Il wire JSON NON cambia (i `null` sono omessi
  da Gson). Lato client, `Cli.payload(res, Class)` lo riconverte nel POJO.

## Vincoli di naming (§4)
- Classi con `main` → nome contenente `"Main"` (es. `ServerMain`, `ClientMain`).
- Codice commentato. Librerie esterne → allegate come jar.

## Decisioni lasciate all'interpretazione (DA DOCUMENTARE nel PDF)
1. Metrica classifica → **cumulative score** (scelto).
2. Durata partita → default 600s (config).
3. Gap tra partite → **immediato** (auto-join `onlineUsers`).
4. Password → **in chiaro** (scelta consapevole; non focalizza sicurezza).
5. Libreria JSON → **Gson** (jar allegato).
6. Codici errore → **testuali** centralizzati in `protocol/Errors` (enum).
7. Sentinel "partita corrente" → `roundId = -1` (INT). `gameId` sorgente si ripete; `roundId` monotono.
8. Persistenza → **utenti + storico partite** (JSON separati), **event-driven senza timer** (deltas account in `register`/`updateCredentials`; stats/storico nel scheduler post-finalize; shutdown hook all'uscita).
9. Outcome per giocatore ∈ {WON, LOST(4 errori), NOT_FINISHED(timeout)}.
10. Mistake Histogram: bin vittorie 0-3 errori + fallite(4) + not_finished.
11. Notifiche UDP → **unicast** a endpoint registrati (`UdpRegistry`: IP TCP + port dal login).

## Convenzioni adottate qui
- Lingua: italiano (match progetto/utente).
- File di questo brain: `README.md` (indice), `status.md` (board),
  `protocol.md` (messaggi/errori/storico), `gotchas.md` (insidie + dove pesa il lavoro),
  `client.md` (piano client: package, thread, comandi, payload, insidie).
- Documentazione per-file speculare in `docs/explain/server/` (1 `.md` per `.java`).
