package server.network;

import server.core.Context;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

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
                pool.submit(new ClientHandler(client, ctx));
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
