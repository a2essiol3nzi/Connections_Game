package client;

import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

// Carica i parametri del client da client.properties.
public class ClientConfig {

    public final String host;
    public final int tcpPort;

    private ClientConfig(Properties p) {
        host = p.getProperty("host", "localhost");
        tcpPort = Integer.parseInt(p.getProperty("port", "12345"));
    }

    public static ClientConfig load(String path) throws IOException {
        Properties p = new Properties();
        try (FileReader r = new FileReader(path)) {
            p.load(r);
        }
        return new ClientConfig(p);
    }
}