# Procedura di chiusura controllata del server

**Data**: 2026-08-24  
**Stato**: proposta / piano  
**Problema**: il server non ha metodi di chiusura "sicuri", può terminare solo su ^C o chiusure forzate del processo.

---

## Analisi dello stato attuale

| Thread | Loop | Risponde a interrupt? | Note |
|--------|------|----------------------|------|
| `ConnectionAcceptor` (main) | `while(true) + accept()` | ❌ No | `accept()` non è interruptibile — solo `socket.close()` lo sblocca |
| `GameScheduler` | `while(true) + sleep()` | ⚠️ Parziale | `sleep()` cattura `InterruptedException` e resetta il flag, ma il loop riparte |
| `PersistThread` | `while(true) + sleep()` | ⚠️ Parziale | Stesso problema |
| Worker pool | `Executors.newFixedThreadPool` | ❌ No | Nessun `shutdown()` registrato |

**Problema**: lo shutdown hook attuale persiste i dati ma **non ferma i thread**. Il processo JVM resta vivo (thread non-daemon in loop infinito). Serve un secondo Ctrl+C (o `kill -9`) per terminare.

---

## Piano di implementazione

### 1. `ConnectionAcceptor` — espone `shutdown()` che chiude il socket

```java
public class ConnectionAcceptor implements Runnable {
    private volatile boolean running = true;
    private ServerSocket serverSocket;  // visibile per chiusura esterna

    @Override
    public void run() {
        try (ServerSocket ss = new ServerSocket(port)) {
            this.serverSocket = ss;
            while (running) {
                Socket client = ss.accept();
                pool.submit(new ClientHandler(client, ctx));
            }
        } catch (IOException e) {
            if (running)  // solo se NON è chiusura volontaria
                System.err.println("[Acceptor] terminato: " + e.getMessage());
        }
    }

    public void shutdown() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
    }
}
```

**Perché**: `accept()` è bloccante e non risponde a `interrupt()`. L'unico modo per sbloccarlo è chiudere il `ServerSocket` da un altro thread. `SocketException` è sottoclasse di `IOException` → catturato dal catch esistente.

### 2. `GameScheduler` — loop controllato da interrupt flag

```java
@Override
public void run() {
    while (!Thread.currentThread().isInterrupted()) {  // era while(true)
        ActiveGame g = gm.current();
        if (g == null) { sleep(1000); continue; }
        long waitMs = g.endTimeMs - System.currentTimeMillis();
        if (waitMs > 0) sleep(waitMs);
        gm.finalizeGame(users);
        // ... persist + notify + rotate ...
    }
    System.out.println("[Scheduler] chiusura controllata");
}

private static void sleep(long ms) {
    try { Thread.sleep(ms); }
    catch (InterruptedException e) {
        Thread.currentThread().interrupt();  // restore flag → esce dal loop
    }
}
```

**Perché**: `InterruptedException` durante `sleep()` resetta il flag. Il `catch` lo ripristina → la prossima iterazione del `while` vede `isInterrupted() == true` e esce. Il `finalizeGame` in corso (synchronized) completa prima dell'uscita.

### 3. `PersistenceThread` — stessa modifica

```java
@Override
public void run() {
    while (!Thread.currentThread().isInterrupted()) {  // era while(true)
        try {
            Thread.sleep(intervalSec * 1000L);
            users.persist();
            games.persistHistory();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;  // esce immediatamente
        } catch (Exception e) {
            System.err.println("[Persist] save failed: " + e.getMessage());
        }
    }
}
```

### 4. `ServerMain` — coordinamento nello shutdown hook

```java
public static void main(String[] args) {
    // ... setup config, loader, context ...

    // Riferimenti ai thread per interrupt
    GameScheduler scheduler = new GameScheduler(ctx.games, ctx.users, ctx.notifier);
    PersistenceThread persister = new PersistenceThread(ctx.users, ctx.games, cfg.persistIntervalSec);
    Thread schedulerThread = new Thread(scheduler, "scheduler");
    Thread persistThread = new Thread(persister, "persist");
    schedulerThread.start();
    persistThread.start();

    var pool = Executors.newFixedThreadPool(cfg.poolSize);
    ConnectionAcceptor acceptor = new ConnectionAcceptor(cfg.tcpPort, pool, ctx);

    // Shutdown hook: SIGTERM/SIGINT → chiusura controllata
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        System.out.println("[Server] shutdown richiesto...");
        acceptor.shutdown();              // chiude ServerSocket → accept() esce
        schedulerThread.interrupt();      // interrompe sleep → scheduler esce
        persistThread.interrupt();        // interrompe sleep → persist esce
        pool.shutdown();                  // non accetta nuovi task
        try {
            pool.awaitTermination(10, TimeUnit.SECONDS);  // attende worker attivi
        } catch (InterruptedException ignored) {}
        // Final persist (dati ultima partita)
        try {
            ctx.users.persist();
            ctx.games.persistHistory();
        } catch (IOException e) {
            System.err.println("[Server] final persist failed: " + e.getMessage());
        }
        System.out.println("[Server] chiusura completata");
    }, "shutdown-hook"));

    System.out.println("[Server] listening on TCP " + cfg.tcpPort + " (UDP " + cfg.udpPort + ")");
    acceptor.run();  // blocking — main thread resta qui
}
```

### 5. Documentazione

Aggiorno i markdown: `ServerMain.md`, `ConnectionAcceptor.md`, `GameScheduler.md`, `PersistenceThread.md`, `gotchas.md`, `status.md`.

---

## Uso

```bash
# Avvio
java -cp build:lib/gson-2.11.0.jar server.core.ServerMain

# Chiusura controllata (da un altro terminale)
kill -SIGTERM <pid>   # oppure kill -SIGINT <pid>
```

Il server:
1. Smette di accettare nuove connessioni
2. Completa la partita corrente (scheduler) e il salvataggio in corso (persist)
3. Attende che i worker finiscano le richieste in corso (max 10s)
4. Salva UserStore + storico un'ultima volta
5. Termina con exit code 0

**Nota su SIGUSR1**: in Java 21 richiede `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` per usare `jdk.internal.misc.Signal`. Funziona ma è fragile (dipende da implementazione JVM). SIGTERM è lo standard Unix per "chiudi gracefully" — lo uso come meccanismo primario. Se vuoi SIGUSR1 lo aggiungo come alias.

---

## Riepilogo modifiche

| File | Modifica |
|------|----------|
| `ServerMain.java` | Riferimenti thread + shutdown hook coordinato |
| `ConnectionAcceptor.java` | `volatile running` + `shutdown()` che chiude socket |
| `GameScheduler.java` | `while(!isInterrupted())` al posto di `while(true)` |
| `PersistenceThread.java` | Stessa modifica |
| 4 doc markdown | Allineate al nuovo comportamento |

**Nessuna nuova dipendenza, nessun pattern aggiunto.** Solo flag volatili + interrupt + close — tutto stdlib.
