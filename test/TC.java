import client.ClientConn;
import com.google.gson.Gson;
import protocol.Request;
import protocol.Response;
import protocol.payload.GameInfoPayload;
import protocol.payload.GameStatsPayload;
import protocol.payload.LeaderboardPayload;
import protocol.payload.PlayerStatsPayload;
import protocol.payload.SubmitPayload;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.DatagramSocket;
import java.util.List;

/**
 * TestClient: wrapper su client.ClientConn. Espone wrapper tipizzati per le 9 
 * operazioni e la riconversione payload->POJO (la stessa del client reale).
 *
 * startServer(): avvia il server su una config temporanea (porte alta + file persist/
 * history in /tmp) così i test NON toccano data/users.json di produzione.
 */
public final class TC implements AutoCloseable {

    private static final Gson GSON = new Gson();
    private static final String HOST = "127.0.0.1";
    private final ClientConn conn;

    public TC(int port, int udpPort) throws IOException {
        conn = new ClientConn(HOST, port);
    }

    @Override 
    public void close() throws IOException { 
        conn.close(); 
    }

    public Response call(Request req) throws IOException { 
        return conn.sendAndRetreive(req); 
    }

    public static Request req(String op) { 
        Request r = new Request(); 
        r.operation = op; 
        return r; 
    }

    // --- operazioni del protocollo -----------------------------------------
    public Response register(String u, String p) throws IOException {
        Request r = req("register"); r.username = u; r.psw = p; 
        return call(r);
    }
    public Response updateCredentials(String oldU, String oldP, String newU, String newP) throws IOException {
        Request r = req("updateCredentials"); r.oldUsername = oldU; r.oldPsw = oldP;
        r.newUsername = newU; r.newPsw = newP; 
        return call(r);
    }
    public Response login(String u, String p, int udpPort) throws IOException {
        Request r = req("login"); r.username = u; r.psw = p; r.udpPort = udpPort; 
        return call(r);
    }
    
    public Response logout() throws IOException { 
        return call(req("logout")); 
    }
    
    public Response submit(List<String> words) throws IOException {
        Request r = req("submitProposal"); r.words = words; 
        return call(r);
    }

    public Response gameInfo(int gameId) throws IOException {
        Request r = req("requestGameInfo"); r.gameId = gameId; 
        return call(r);
    }

    public Response gameStats(int gameId) throws IOException {
        Request r = req("requestGameStats"); r.gameId = gameId; 
        return call(r);
    }

    public Response leaderboard(String playerName, Integer topN) throws IOException {
        Request r = req("requestLeaderboard"); r.playerName = playerName; r.topPlayers = topN; 
        return call(r);
    }

    public Response playerStats() throws IOException { 
        return call(req("requestPlayerStats"));
    }

    // --- riconversione payload generico -> POJO (come Cli.payload) -----------
    public GameInfoPayload asGameInfo(Response r) { return GSON.fromJson(GSON.toJson(r.payload), GameInfoPayload.class); }
    public GameStatsPayload asStats(Response r) { return GSON.fromJson(GSON.toJson(r.payload), GameStatsPayload.class); }
    public LeaderboardPayload asLb(Response r) { return GSON.fromJson(GSON.toJson(r.payload), LeaderboardPayload.class); }
    public PlayerStatsPayload asPlayer(Response r) { return GSON.fromJson(GSON.toJson(r.payload), PlayerStatsPayload.class); }
    public SubmitPayload asSubmit(Response r) { return GSON.fromJson(GSON.toJson(r.payload), SubmitPayload.class); }

    // --- avvio server su config temporanea ----------------------------------
    public static class Server {
        public final Process proc;
        public final int tcpPort;

        public Server(Process proc, int tcpPort) { this.proc = proc; this.tcpPort = tcpPort; }

        public void stop() { 
            proc.destroy(); 
            try { proc.waitFor(); } 
            catch (InterruptedException e) { proc.destroyForcibly(); } 
        }
    }

    private static int freeTcpPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { 
            return s.getLocalPort(); 
        }
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket s = new DatagramSocket(0)) { 
            return s.getLocalPort(); 
        }
    }

    // Avvia il server su una config temporanea. Ritorna handle con proc+porta.
    public static Server startServer(String projectDir, int gameDurationSec) throws IOException, InterruptedException {
        int tcp = freeTcpPort();
        int udp = freeUdpPort();
        String tmp = "/tmp/conn_test_" + System.nanoTime();
        new java.io.File(tmp).mkdirs();
        String cfgFile = tmp + "/server.properties";
        try (BufferedWriter w = new BufferedWriter(new FileWriter(cfgFile))) {
            w.write("tcp.port=" + tcp + "\n");
            w.write("udp.port=" + udp + "\n");
            w.write("game.duration.sec=" + gameDurationSec + "\n");
            w.write("pool.size=16\n");
            w.write("games.file=" + projectDir + "/data/games.json\n");
            w.write("persist.file=" + tmp + "/users.json\n");
            w.write("history.file=" + tmp + "/history.json\n");
            w.write("persist.interval.sec=300\n");
        }
        String cp = "out:lib/gson-2.11.0.jar";
        Process proc = new ProcessBuilder("java", "-cp", cp, "server.core.ServerMain", cfgFile)
                .directory(new java.io.File(projectDir))
                .redirectErrorStream(true).start();
        // attende che il server ASCOLTI sulla porta tcp.
        //  - bind RIUSCITO  => porta ancora LIBERA, il server non è ancora up.
        //  - bind FALLITO   => porta OCCUPATA dal server attivo.
        long deadline = System.currentTimeMillis() + 15000;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive())
                throw new IOException("server exited early");
            try (ServerSocket s = new ServerSocket()) {
                s.bind(new java.net.InetSocketAddress(HOST, tcp));
                s.close();
                Thread.sleep(200);
                continue;
            } catch (IOException e) { ready = true; break; }
        }
        if (!ready) { 
            proc.destroyForcibly(); 
            throw new IOException("server non si e' avviato in tempo"); 
        }
        Thread.sleep(300);
        return new Server(proc, tcp);
    }
}