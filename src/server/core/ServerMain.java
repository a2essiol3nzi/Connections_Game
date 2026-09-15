package server.core;

import server.loader.GameLoader;
import server.network.ConnectionAcceptor;
import server.network.GameScheduler;

import java.io.IOException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Entry point del server.
 *
 * Sequenza di avvio:
 *   1. legge la config server.properties;
 *   2. crea il GameLoader (streaming Gson, memoria O(1): nessuna partita in RAM);
 *   3. costruisce il Context (risorse condivise);
 *   4. avvia lo scheduler partita (la persistenza è EVENT-DRIVEN);
 *   5. registra lo shutdown hook (su SIGTERM/SIGINT salva UserStore su disco);
 *   6. apre l'acceptor TCP sul thread principale (bloccante).
 */
public class ServerMain {

    public static void main(String[] args) {
        String cfgPath = "server.properties";

        // 1) config
        ServerConfig cfg;
        try {
            cfg = ServerConfig.load(cfgPath);
        } catch (IOException e) {
            System.err.println("[Server] config illeggibile '" + cfgPath + "': " + e.getMessage());
            System.exit(2);
            return;
        }

        // 2) loader pigro (streaming Gson, memoria O(1))
        GameLoader loader;
        try {
            loader = new GameLoader(cfg.gamesFile);
        } catch (IOException e) {
            System.err.println("[Server] partite illeggibili '" + cfg.gamesFile + "': " + e.getMessage());
            System.exit(3);
            return;
        }
        System.out.println("[Server] prepared " + loader.total() + " games from " + cfg.gamesFile);

        // 3) risorse condivise
        Context ctx = new Context(cfg, loader);

        // 4) scheduler partita (persistenza utenti event-driven)
        GameScheduler scheduler = new GameScheduler(ctx.games, ctx.users, ctx.notifier, cfg.gameDurationSec);

        // 5) acceptor TCP sul thread principale
        // Pool a crescita on-demand: core 0 (nessun thread in attesa se non ci sono client),
        // massimo pool.size, thread idle muoiono dopo 10s. SynchronousQueue consegna ogni
        // task direttamente a un worker; in saturazione (tutti i worker occupati) la AbortPolicy 
        // RIFIUTA il task lanciando RejectedExecutionException: l'acceptor chiude la connessione 
        // del client (che si disconnette) invece di accumulare richieste o bloccare.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            0, cfg.poolSize,
            10, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new ThreadPoolExecutor.AbortPolicy()
        );
        ConnectionAcceptor acceptor = new ConnectionAcceptor(cfg.tcpPort, pool, ctx);

        // 6) shutdown hook: ferma prima tutti i mutatori, poi salva uno stato stabile.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            acceptor.shutdown();
            scheduler.shutdown();
            shutdownPool(pool);
            try {
                ctx.users.persistUsers();
                ctx.games.persistHistory();
                System.out.println("[Server] users + history saved on shutdown");
            } catch (IOException e) {
                System.err.println("[Server] shutdown persist failed: " + e.getMessage());
            }
        }, "shutdown-hook"));

        scheduler.start();
        System.out.println("[Server] listening on TCP " + cfg.tcpPort);
        acceptor.run();
    }

    // I socket sono già chiusi dall'acceptor, quindi gli handler bloccati su readLine()
    // escono; attende che nessuno modifichi lo stato durante la persistenza finale.
    private static void shutdownPool(ThreadPoolExecutor pool) {
        pool.shutdown();
        try {
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
