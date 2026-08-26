package client;

import com.google.gson.Gson;
import protocol.Request;
import protocol.Response;
import protocol.GameEnded;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

/**
 * Riceve le notifiche async UDP (fine partita) inviate dal server.
 *
 * La porta è EFFIMERA (binding a 0): il valore va inviato al server nel login
 * come `udpPort`. `GAME_ENDED` è SOLO un segnale (gameId/roundId), non contiene
 * i risultati: al suo arrivo si va a leggere l'esito via TCP con
 * requestGameInfo(roundId). Se la notifica arriva prima che il server abbia
 * finalizzato (TOCTOU) la lettura restituisce ERR_GAME_NOT_FOUND: si ritenta 
 * per un numero finito di volte poi si abbandona.
 */
public class UdpClient implements Runnable, AutoCloseable {

    private static final Gson GSON = new Gson();
    private static final int MAX_RETRY = 10;   // ~2s guida (per TOCTOU-window)
    private static final long RETRY_DELAY_MS = 200;

    private final DatagramSocket sock;
    private final ClientConn conn;
    private volatile boolean running = true;

    public UdpClient(ClientConn conn) throws IOException {
        this.conn = conn;
        this.sock = new DatagramSocket(0); // porta effimera, da comunicare al login
    }

    public int port() { return sock.getLocalPort(); }

    @Override
    public void run() {
        byte[] buf = new byte[512]; // dim per evitare che il dat venga spezzettato
        while (running) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                sock.receive(pkt);
            } catch (IOException e) {
                if (running) System.err.println("[udp] receive error: " + e.getMessage());
                continue;
            }
            String json = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
            try {
                GameEnded ge = GSON.fromJson(json, GameEnded.class);
                if (ge == null || !"GAME_ENDED".equals(ge.type))
                    continue;
                printResult(ge.roundId);
            } catch (Exception e) {
                // payload UDP ignoto, ignora
            }
        }
    }

    // Legge l'esito della partita conclusa con retry.
    private void printResult(int roundId) {
        System.out.println("\n[notifica] partita round " + roundId + " terminata - recupero esito...");
        Request req = new Request();
        req.operation = "requestGameInfo";
        req.gameId = roundId;
        for (int i = 0; i < MAX_RETRY; i++) {
            try {
                Response r = conn.sendAndRetreive(req);
                if ("ERROR".equals(r.status)) {
                    if ("ERR_GAME_NOT_FOUND".equals(r.errorCode)) {
                        // storico non ancora finalizzato (TOCTOU)
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }
                    System.out.println("\tesito non disponibile: " + r.errorCode);
                    return;
                }
                System.out.println("=== ESITO PARTITA round " + roundId + " ===");
                Cli.renderGameInfo(r.payload);
                System.out.println("========================================");
                return;
            } catch (IOException e) { // per abbandono o altro
                System.out.println("[udp] connessione TCP chiusa, abbandono");
                return;
            } catch (InterruptedException e) { // su sleep
                return;
            }
        }
        System.out.println("\tesito non ancora pronto dopo " + MAX_RETRY + " tentativi");
    }

    @Override 
    public void close() { running = false; sock.close(); }
}