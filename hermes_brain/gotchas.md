# GOTCHAS & DECISIONI — Server Connections

## Regole ferree (vincoli progetto)
- **Java 8** (target). `Makefile` compila con `--release 8`: vietato usare API/sintassi
  Java 9+ (`var`, `List.of`, `Path.of`, `isBlank`, text block `"""`, …). Sotto JDK 21
  le violazioni **non** danno errore senza `--release`, quindi vanno sempre compilate lì.
- **Concorrenza**: solo `synchronized` / `wait()`/`notifyAll()` / `java.util.concurrent.atomic.*`.
  VIETATI `java.util.concurrent.locks.*` (ReentrantLock, ReadWriteLock, …).
- **JSON**: GSON 2.11.0 (`lib/gson-2.11.0.jar`), non hand-rolled.
- **NIO**: lato CLIENT obbligatorio (§3); server usa I/O bloccante (ammesso).
- **UDP**: notifiche async fine partita; **unicast** (no multicast, nuovo ordinamento).
- **File partite**: schema array top-level `[{"gameId":int,"groups":[{"theme":str,"words":[4]}]}]`;
  `theme` MAI al client live (finisce nello storico `groupInfo`, non nelle info live). ~620KiB fornito dai docenti.
- **Persistenza**: JSON utenti **+ JSON storico partite**; periodica + atomica (tmp+rename). Due file separati.
- **Naming**: classi con `main` contengono "Main" (§4). Sorgenti server in `core/loader/model/network/persistence`; messaggi condivisi in `protocol` (top-level).
- **Config**: da `.properties`, NO CLI né interattivo.

## Scelte confermate (default doc PDF)
- Leaderboard = **cumulative score**. Gap tra partite = **immediato** (auto-join degli `onlineUsers` alla rotazione).
- `roundId = -1` (non `gameId`) ⇒ partita corrente nelle richieste. `gameId` sorgente si ripete al wrap; `roundId` è monotono e univoco.
- Identificazione **per userId immutabile** ovunque (storico, leaderboard, handler, UDP registry): sopravvive alla rinomina in partita.
- Durata default 600s.

## Convenzione errori (REFACTOR)
- I metodi `UserStore`/`GameManager`/`ActiveGame` ritornano `null` = OK, oppure un valore
  `Errors` (enum). `ActiveGame.JoinResult`/`SubmitResult` incapsulano l'enum `Errors`
  (`error()`/`isOk()`), così `ClientHandler` mappa 1:1 senza letterali sparsi.
- `ERR_ALREADY_LOGGED_IN` è il codice per login duplicato: `GameManager.registerLogin`
  ritorna `false` se già online → handler risponde `ERR_ALREADY_LOGGED_IN`. Aggiunti
  `BAD_REQUEST` (JSON illeggibile/operation mancante/udpPort assente) e
  `ERR_PLAYER_NOT_FOUND`.
- **Anti-enumerazione login**: `UserStore.login` restituisce
  `ERR_INVALID_CREDENTIALS` sia per username inesistente sia per password errata.
  `updateCredentials` conserva `ERR_USER_NOT_FOUND` per `oldUsername` inesistente,
  ma restituisce `ERR_INVALID_CREDENTIALS` per la sola `oldPsw` errata.

## Bug caught da verifica
- `GameLoader` streaming con `CountingInputStream` → offset errati. Fix: `RandomAccessFile`+span → poi **`JsonReader`+`skipValue`** (O(1), robusto su oggetti malformati a runtime).
- `UserStore.login/register/...` ritornavano `"OK"` (stringa) → trattati come errore. Fix: ritornano `null`=OK, `Errors`=errore (enum unico).
- `UserStore.load()` catch solo `IOException` → JSON malformato faceva non partire. Fix: `catch (Exception)` + rinomina `.corrupt` + partenza vuota.
- **Race submit/join vs rotate**: lo scheduler poteva finalizzare mentre un handler valutava. Fix: `ActiveGame.finalized` (`AtomicBoolean`) sigilla `submit`/`join` dopo la rotazione; `login` in `ClientHandler` usa `synchronized(ctx.games)` (anti-TOCTOU tra `current()` e l'azione). `submitProposal` invece NON è più `synchronized`: la TOCTOU la gestiscono `finalized` + `finalizeGame` idempotente (vedi REFACTOR SYNC sotto).
- **Lock di `submit` troppo grossolano**: `synchronized(this)` su `ActiveGame` serializzava tutte le proposte. Fix: **lock granulare su `PlayerState`** (`synchronized(ps)`) — N client sottomettono in parallelo; validazioni read-only (groups/shuffledWords immutabili) fuori lock; ri-check di `finalized` dentro il lock.
- **REFACTOR SYNC (bottleneck monitor `GameManager`)**: il monitor globale di `GameManager` serializzava TUTTE le operazioni (submit, gameInfo, persist). Fix:
  - `current` → **`volatile`**, `current()` **lock-free** (visibilità senza lock; assegnato solo in `rotate()`/costruttore, prima della pubblicazione).
  - `submitProposal` **non più `synchronized`**: la TOCTOU è già gestita da `ActiveGame.finalized` (CAS) + `finalizeGame` idempotente → un submit sull'ex-partita torna `ERR_FINISHED`. Uscito il monitor, il lock per-`PlayerState` lavora davvero (submit paralleli).
  - `handleProposal` in `ClientHandler` rimosso il `synchronized(ctx.games)` (era double-lock ridondante).
  - `finalizeGame` rilegge `PlayerState` sotto **`synchronized(ps)`** esplicito — prima era protetto solo dall'implicito monitor grosso; senza, le letture di `correctCount/errors/score` in finalizzazione gappano contro submit concorrenti. No deadlock (finalize prende GM→ps; submit solo ps).
  - `persistHistory` / `UserStore.persist`: **NON più `synchronized` sul monitor business**; build in-memory sotto `this`, **disk I/O su `ioLock`** dedicato (serializza le fonti senza bloccare register/login/submit durante la scrittura). `persistHistory` usa **`Gson.toJson(map, w)` streamed** → niente `String`/`JsonElement` intermedia dell'intero storico (memoria extra O(1)).
- **RIMOSSO il `PersistenceThread` (persistenza event-driven)**: il timer salvava ogni 30s un intero snapshot, quasi sempre su dati invariati; stats/storico li salvava già comunque lo scheduler post-finalize + shutdown hook. Il suo unico lavoro reale (deltas account) ora è coperto da **`persistQuiet()` chiamato in `UserStore.register`/`updateCredentials` DOPO il `synchronized(this)`** (fuori dal lock): i deltas vanno su disco appena avvengono → perdita a hard-crash = 0 (prima ≤30s). Rimosso anche `ServerConfig.persistIntervalSec` e la riga `persist.interval.sec` da `server.properties`. Scrittore concorrente ridotto a 2 (register/rename, scheduler), serializzati su `ioLock`. Tradeoff: `register`/`updateCredentials` ora fanno disk I/O sul path di richiesta — costo piccolo (scrittura atomica, evento raro) e nascosto da `persistQuiet()` che logga gli `IOException` senza tradurli in `Errors` del client.
- **Storico per username (B9)**: la rinomina invalidava l'accesso allo storico. Fix: chiave dello storico = **userId immutabile** (`entries` per id in `GameHistory`).
- **Finalize multiplo**: scheduler + shutdown hook + timer potevano contare 2 volte. Fix: `finalizeGame` **idempotente** via `finalized.compareAndSet(false,true)`.
- **`sleep()` dello scheduler usciva al primo `InterruptedException`**: interrompendo il thread si saltava la attesa e si procedeva a finalize/rotate anticipati. Fix: `sleep()` ricalcola il deadline e **ritenta** finché non scaduto (`while` + `rem = deadline - now`).
- **Notifica UDP scambiata per risultato**: la `GAME_ENDED` è solo un *segnale* (gameId/roundId), non contiene l'esito. Se arriva prima di `finalizeGame` si apre una finestra TOCTOU (storico non ancora scritto). Fix (lato client, da implementare): al segnale, leggere l'esito via TCP `requestGameInfo(roundId)`/`requestGameStats` con retry/attesa. Lato server: `UdpNotifier` usa un solo `DatagramSocket` riusato con `connect()` per destinatario (non bloccante verso host irraggiungibili).
- **`gameStats` live contava i "fantasmi"**: `participantsTotal` era `players.size()` = tutti i GIUNTI, anche i disconnessi (logout/EOF non ripuliva mai `players`). Fix: **live conta `onlineUsers ∩ players`** (chi è ancora connesso); chi abbandona esce subito dal conteggio. `players` NON va ripulita (a fine round `finalizeGame` la itera per storico + stats cumulative — rimuovere il PlayerState perderebbe dati). Client stampa "(connessi)" accanto al conteggio live. Lo STORICO resta invariato (conta tutti i completati).
- **Ripartire dal gameId lasciato (anti-furto ordine)**: l'ordine del ciclo è deterministico; se a ogni riavvio si ripartisse da `gameId 0` (primo del file), un utente che conosce l'ordine del file saprebbe in anticipo quali partite arrivano. Fix: nel costruttore `GameManager` dopo `loadHistory()`, `resumeFromRound()` avanza l'iteratore di `(nextRoundId-1) % total` (= `roundId % total` col roundId = round già giocati) e scarta, così si riparte dalla partita successiva a quella mostrata prima dello shutdown. `loadHistory` ricostruisce `nextRoundId` dal massimo roundId dello storico, quindi il conteggio è persistito tra i riavvii.

## Note utente (stile)
- Commenti estesi OK; lui li sintetizzerà. Mantenere filosofia di scrittura attuale.
- Password in chiaro: semplificazione voluta (non focalizza sicurezza).
- Id utente immutabile (consiglio prof): `<id,User>` primaria + `<username,id>` indice; `getById` per risoluzione stabile.

## Refactor payload POJO (convenzione)
- `protocol/payload/` contiene UN POJO per ogni risposta OK; `Response.payload` è
  **`Object`** (il tipo è determinato dall'operazione). Gson emette i `null` come
  assenti ⇒ **il wire JSON è invariato** rispetto al precedente `JsonObject`.
- Lato client non si leggono più le chiavi con `.get("...")`: si riconverte il
  payload col POJO dell'operazione (`GSON.fromJson(GSON.toJson(res.payload), Cls.class)` —
  vedi `client/Cli.payload`).
- Mai fare cast diretto `(GameInfoPayload) res.payload`: il payload è un `Object`
  deserializzato da Gson senza tipo, serve la riconversione esplicita.
