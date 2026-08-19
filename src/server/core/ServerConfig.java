package server.core;

import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

// Carica i parametri del server da un file .properties all'avvio
public class ServerConfig {

    public final int tcpPort;
    public final int udpPort;
    public final int gameDurationSec;
    public final int poolSize;
    public final int persistIntervalSec;
    public final String gamesFile;
    public final String persistFile;

    private ServerConfig(Properties p) {
        tcpPort = Integer.parseInt(p.getProperty("tcp.port", "12345"));
        udpPort = Integer.parseInt(p.getProperty("udp.port", "12346"));
        gameDurationSec = Integer.parseInt(p.getProperty("game.duration.sec", "600"));
        poolSize = Integer.parseInt(p.getProperty("pool.size", "16"));
        persistIntervalSec = Integer.parseInt(p.getProperty("persist.interval.sec", "30"));
        gamesFile = p.getProperty("games.file", "data/games.json");
        persistFile = p.getProperty("persist.file", "data/users.json");
    }

    public static ServerConfig load(String path) throws IOException {
        Properties p = new Properties();
        try (FileReader r = new FileReader(path)) {
            p.load(r);
        }
        return new ServerConfig(p);
    }
}
