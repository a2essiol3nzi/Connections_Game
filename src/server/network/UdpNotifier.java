package server.network;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Notificher UDP unicast: invia notifiche fine partita ai partecipanti
 * usando il registro endpoint (userId -> InetAddress:port).
 */
public class UdpNotifier {

    private static final Gson GSON = new Gson();
    private final UdpRegistry registry;

    public UdpNotifier(UdpRegistry registry) { 
        this.registry = registry;
    }

    // Invia notifiche di fine partita via UDP unicast ad ogni partecipante.
    // `payload` è un POJO (GameEnded) serializzato con Gson: il notifier non
    // dipende dal formato specifico della notifica.
    public void notifyEnd(Set<Integer> participants, Object payload) {
        byte[] data = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        // Payload e un solo DatagramPacket riusati per tutti i destinatari:
        // per ogni endpoint si aggiorna solo la destinazione. UDP non attende
        // conferme dal destinatario.
        try (DatagramSocket sock = new DatagramSocket()) {
            DatagramPacket pkt = new DatagramPacket(data, data.length);
            for (int userId : participants) {
                Object[] ep = registry.getEndpoint(userId);
                if (ep == null) {
                    System.err.println("[UdpNotifier] No endpoint registered for userId " + userId);
                    continue;
                }
                InetAddress addr = (InetAddress) ep[0];
                int port = (Integer) ep[1];
                pkt.setAddress(addr);
                pkt.setPort(port);
                try {
                    sock.send(pkt);
                    System.out.println("[UdpNotifier] Notified userId " + userId + " at " + addr.getHostAddress() + ":" + port);
                } catch (IOException e) {
                    System.err.println("[UdpNotifier] Failed to notify userId " + userId + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[UdpNotifier] Failed to notify: " + e.getMessage());
        }
    }
}
