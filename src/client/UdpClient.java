package client;

import com.google.gson.Gson;
import protocol.Request;
import protocol.Response;
import protocol.GameEnded;
import protocol.payload.GameInfoPayload;

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
 * requestGameInfo(roundId). Lo scheduler ruota prima di inviare la notifica,
 * quindi il round richiesto è già nello storico e la lettura è immediata.
 */
public class UdpClient implements Runnable, AutoCloseable {

    private static final Gson GSON = new Gson();

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

    // Legge l'esito storico della partita conclusa.
    private void printResult(int roundId) {
        Cli.clearInputLine(); // pulisci stdin di client
        System.out.println("\n[notifica] partita round " + roundId + " terminata - recupero esito...");
        Request req = new Request();
        req.operation = "requestGameInfo";
        req.roundId = roundId;
        try {
            Response r = conn.sendAndRetreive(req);
            if ("ERROR".equals(r.status)) {
                System.out.println("\tesito non disponibile: " + r.errorCode);
                Cli.printPrompt(); // ridisegna prompt
                return;
            }
            System.out.println("┈┈┈ ESITO PARTITA round " + roundId + " ┈┈┈");
            Cli.renderGameInfo(GSON.fromJson(GSON.toJson(r.payload), GameInfoPayload.class));
            System.out.println("┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈");
            Cli.printPrompt();
        } catch (IOException e) { // per abbandono o altro
            System.out.println("[udp] connessione TCP chiusa, abbandono");
        }
    }

    @Override 
    public void close() { running = false; sock.close(); }
}