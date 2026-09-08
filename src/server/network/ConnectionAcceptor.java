package server.network;

import server.core.Context;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
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

    public ConnectionAcceptor(int port, ExecutorService pool, Context ctx) {
        this.port = port;
        this.pool = pool;
        this.ctx = ctx;
    }

    @Override
    public void run() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket client = serverSocket.accept();
                try {
                    pool.submit(new ClientHandler(client, ctx));
                } catch (RejectedExecutionException e) {
                    // Pool saturo (tutti i worker occupati): RIFIUTA la connessione. Il socket
                    // non è stato preso in carico da alcun worker: chiudendolo il client viene
                    // disconnesso (e può decidere di ritentare) invece di accumularsi in coda.
                    System.err.println("[Acceptor] pool saturo, rifiuto " + client.getInetAddress());
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
            System.err.println("[Acceptor] terminato: " + e.getMessage());
            System.exit(1);
        }
    }
}
