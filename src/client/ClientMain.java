package client;

import java.io.IOException;

/**
 * Entry point del client.
 *
 * Sequenza di avvio:
 *   1. legge client.properties (host, porta TCP);
 *   2. apre la connessione TCP persistente (NIO);
 *   3. crea il receiver UDP su porta effimera (il valore viene inviato nel login);
 *   4. avvia il thread UDP di notifiche;
 *   5. esegue il loop CLI sul thread principale.
 */
public class ClientMain {

    public static void main(String[] args) {
        String cfgPath = (args.length > 0) ? args[0] : "client.properties";

        // 1) config
        ClientConfig cfg;
        try {
            cfg = ClientConfig.load(cfgPath);
        } catch (IOException e) {
            System.err.println("[client] config illeggibile '" + cfgPath + "': " + e.getMessage());
            System.exit(2);
            return;
        }

        // 2,3) Connesione TCP (NIO) + UDP receiver
        try (
            ClientConn conn = new ClientConn(cfg.host, cfg.tcpPort);
            UdpClient udp = new UdpClient(conn)
        ) {
            System.out.println("[client] connesso a " + cfg.host + ":" + cfg.tcpPort
                + " (UDP notifiche su porta " + udp.port() + ")");
            // 4) Thread notifiche
            new Thread(udp, "udp").start();
            // 5) (Main thread) Gestore CLI
            new Cli(conn, udp.port()).run();
        } catch (IOException e) {
            System.err.println("[client] errore di connessione: " + e.getMessage());
            System.exit(1);
        }
    }
}