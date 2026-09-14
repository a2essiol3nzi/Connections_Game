package server.network;

import server.core.Context;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Accetta connessioni TCP e le smista a thread del pool.
 * Una connessione per client resta aperta per tutta la sessione (persistente).
 */
public class ConnectionAcceptor implements Runnable {

    private final int port;
    private final ExecutorService pool;
    private final Context ctx;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile boolean shuttingDown;

    public ConnectionAcceptor(int port, ExecutorService pool, Context ctx) {
        this.port = port;
        this.pool = pool;
        this.ctx = ctx;
    }

    @Override
    public void run() {
        try (ServerSocket welcomeSock = new ServerSocket(port)) {
            serverSocket = welcomeSock;
            while (!shuttingDown) {
                Socket client = welcomeSock.accept();
                clients.add(client);
                if (shuttingDown) {
                    clients.remove(client);
                    client.close();
                    break;
                }
                try {
                    pool.submit(() -> {
                        try {
                            new ClientHandler(client, ctx).run();
                        } finally {
                            clients.remove(client);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    // Pool saturo (tutti i worker occupati): RIFIUTA la connessione. Il socket
                    // non è stato preso in carico da alcun worker: chiudendolo il client viene
                    // disconnesso (e può decidere di ritentare) invece di accumularsi in coda.
                    System.err.println("[Acceptor] pool saturo, rifiuto " + client.getInetAddress());
                    clients.remove(client);
                    try {
                        client.close();
                    } catch (IOException ignored) {}
                }
            }
        } catch (IOException e) {
            // L'acceptor è l'unico loop di vita del server: se la ServerSocket
            // fallisce (es. porta già in uso) il processo è finito. System.exit
            // forza la terminazione. I thread helper (scheduler/persist) sono
            // non-daemon e terrebbero il JVM appeso se li lasciassimo in vita.
            if (!shuttingDown) {
                System.err.println("[Acceptor] terminato: " + e.getMessage());
                System.exit(1);
            }
        } finally {
            this.serverSocket = null;
        }
    }

    /**
     * Chiude il listener e i socket assegnati ai worker: accept() e readLine()
     * si sbloccano, così il pool può terminare prima della persistenza finale.
     */
    public void shutdown() {
        shuttingDown = true;
        ServerSocket listener = serverSocket;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {}
        }
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }
}
