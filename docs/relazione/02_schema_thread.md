# Schema generale dei thread

Le scelte di concorrenza sono separate in due viste indipendenti: il **server**, che accetta più client e coordina la partita, e il **client**, che ne usa solo due per isolare l'ascolto delle notifiche asincrone dal loop dei comandi.

## Lato server

Il server attiva quattro gruppi di thread, uno dei quali a cardinalità variabile (il pool).

> Vedere immagine `server_system_model`.

### Thread 1 — `main` (1): wiring e accettazione connessioni

Il thread principale (`server.core.ServerMain.main`) svolge i preparativi di avvio: carica la configurazione, costruisce il loader streaming delle partite, il `Context` con le risorse condivise (store utenti, gestore partite, registro/notifier UDP), avvia lo scheduler e registra lo shutdown hook. Quindi crea il **thread pool** e, senza terminare, entra nel loop bloccante di `ConnectionAcceptor.accept()`. Su ogni nuova connessione TCP il lavoro viene **inviato al pool** (`pool.submit(new ClientHandler(...))`); il thread main non si occupa mai di singoli client.

### Thread 2 — `scheduler` (1): ciclo di gioco

Un singolo thread (`network/GameScheduler`) scandisce la durata della partita. A ogni scadenza esegue: `finalizeGame` (idempotente, una sola finalizzazione), persistenza event-driven di utenti e storico, notifica UDP `notifyEnd` (solo segnale) ai client iscritti, quindi la rotazione alla partita successiva con auto-iscrizione dei giocatori online. Gestisce anche `InterruptedException` ricalcolando la deadline anziché uscire.

### Thread 3 — shutdown hook (1): chiusura pulita

Registrato tramite `Runtime.addShutdownHook`, si attiva su `SIGTERM`/`SIGINT`. Il suo unico compito è la persistenza finale: salva utenti (`users.persist()`) e storico partite (`persistHistory()`) prima che il processo termini.

### Thread pool — `N ClientHandler` (da 0 a N, on-demand)

Un `ThreadPoolExecutor` configurato per **non tenere thread inattivi**: core size **0**, massimo **`pool.size`** (default 16, configurabile), keep-alive **10 s**, coda **`SynchronousQueue`**, e una politica di rifiuto **AbortPolicy**. Ogni connessione TCP spawne un worker su richiesta (`pool.submit(new ClientHandler(...))`); se non ci sono client, il pool resta a **zero thread**. I worker idle vengono terminati dopo 10 s di inattività. In saturazione (tutti i `pool.size` worker occupati) la `SynchronousQueue` (queue di capacità 0) non accetta altri task e **`AbortPolicy` rifiuta** il subito, lanciando `RejectedExecutionException`; l'acceptor intercetta l'eccezione e **chiude il socket** della connessione: i task non si accumulano in coda e il client in eccesso viene disconnesso (può ritentare). `N` è quindi sia il massimo dei worker sia il massimo dei client serviti simultaneamente.

## Lato client

Il client usa esattamente **due thread**.

> Vedere immagine `client_system_model`.

### Thread 1 — `main` (1): avvio e loop CLI

Il thread principale (`client.ClientMain`) carica la configurazione, apre la connessione TCP NIO, crea il receiver UDP su porta effimera, avvia il thread delle notifiche e infine esegue in prima persona il loop CLI (`Cli.run()`): legge input da stdin, costruisce la `Request`, la invia e renderizza la `Response`. Niente pool lato client: un solo thread di interazione.

### Thread 2 — `udp` (1): receiver di notifiche

Un unico thread (`client.UdpClient`, nome `"udp"`) resta in attesa su un `DatagramSocket` per le notifiche asincrone `GAME_ENDED`. All'arrivo richiede l'esito tramite la connessione TCP con retry (anti-TOCTOU) e lo stampa con il renderer condiviso. È separato dal thread main perché `receive()` è bloccante e non deve fermare il loop dei comandi.

## Sincronizzazione tra i thread del client

I due thread client condividono il **medesimo** oggetto `ClientConn`. Il metodo `sendAndRetreive()` è **`synchronized`**: un unico lock copre scrittura della richiesta e lettura della risposta, così il thread main e il thread `udp` non intrecciano mai le righe JSON sul canale. Lo stato di gioco vive sul server; sul client non esiste stato condiviso da proteggere oltre alla connessione.

