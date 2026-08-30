package server.core;

import server.loader.GameLoader;
import server.network.ConnectionAcceptor;
import server.network.GameScheduler;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point del server.
 *
 * Sequenza di avvio:
 *   1. legge la config (server.properties o path passato come argomento);
 *   2. crea il GameLoader (streaming Gson, memoria O(1): nessuna partita in RAM);
 *   3. costruisce il Context (risorse condivise);
 *   4. avvia lo scheduler partita (la persistenza è EVENT-DRIVEN);
 *   5. registra lo shutdown hook (su SIGTERM/SIGINT salva UserStore su disco);
 *   6. apre l'acceptor TCP sul thread principale (bloccante).
 */
public class ServerMain {

    public static void main(String[] args) {
        String cfgPath = (args.length > 0) ? args[0] : "server.properties";

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
        new Thread(new GameScheduler(ctx.games, ctx.users, ctx.notifier), "scheduler").start();

        // 5) shutdown hook: SIGTERM/SIGINT -> persist prima di uscire (no perdita ultima partita)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                ctx.users.persist();
                ctx.games.persistHistory();
                System.out.println("[Server] users + history saved on shutdown");
            } catch (IOException e) {
                System.err.println("[Server] shutdown persist failed: " + e.getMessage());
            }
        }));

        // 6) acceptor TCP sul thread principale
        ExecutorService pool = Executors.newFixedThreadPool(cfg.poolSize);
        ConnectionAcceptor acceptor = new ConnectionAcceptor(cfg.tcpPort, pool, ctx);
        System.out.println("[Server] listening on TCP " + cfg.tcpPort + " (UDP " + cfg.udpPort + ")");
        acceptor.run();
    }
}