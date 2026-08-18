package server.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Invia notifiche async di fine partita ai partecipanti via UDP (§2.2, §3).
 * Unicast per partecipante (no multicast: siamo nuovo ordinamento).
 * Pacchetto `network`: comunicazione socket lato server.
 */
public class UdpNotifier {

    private static final Gson GSON = new Gson();
    private final int udpPort;

    public UdpNotifier(int udpPort) { this.udpPort = udpPort; }

    public void notifyEnd(Set<String> participants, JsonObject payload) {
        byte[] data = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket sock = new DatagramSocket()) {
            InetAddress addr = InetAddress.getLoopbackAddress(); // client su stessa macchina
            DatagramPacket pkt = new DatagramPacket(data, data.length, addr, udpPort);
            sock.send(pkt);
        } catch (IOException e) {
            System.err.println("[UdpNotifier] send failed: " + e.getMessage());
        }
    }
}
