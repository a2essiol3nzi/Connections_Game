package client;

import com.google.gson.Gson;
import protocol.Request;
import protocol.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Connessione TCP persistente lato client in NIO bloccante (SocketChannel).
 *
 * Il server usa una riga JSON per ogni risposta (`\n`). Le chiamate sono
 * serializzate (synchronized): un unico lock copre scrivi+leggi, così il thread
 * CLI principale e l'eventuale fetch dall'UdpClient non intrecciano le righe.
 */
public class ClientConn implements AutoCloseable {

    private static final int BUF_CAP = 65536;
    private static final Gson GSON = new Gson();

    private final SocketChannel chan; // canale TCP NIO in modalità bloccante
    private final ByteBuffer netBuff = ByteBuffer.allocate(BUF_CAP);
    private final ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
    private final ArrayDeque<String> pendingLines = new ArrayDeque<>();

    public ClientConn(String host, int port) throws IOException {
        chan = SocketChannel.open(new InetSocketAddress(host, port));
    }

    /**
     * Invia una richiesta e legge la riga-risposta corrispondente.
     * Scrive e legge in un'unica regione critica, quindi il request/response
     * è atomico rispetto agli altri chiamanti che condividono lo stesso ClientConn
     * (thread CLI + fetch UDP).
     * Senza questo lock due chiamate concorrenti mescolerebbero le righe JSON sul canale.
     */
    public synchronized Response sendAndRetreive(Request req) throws IOException {
        writeAll(ByteBuffer.wrap((GSON.toJson(req) + "\n").getBytes(StandardCharsets.UTF_8)));
        return readLine();
    }

    // Scrive tutto il buffer sul canale NIO bloccante.
    private void writeAll(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining())
            chan.write(buffer);
    }

    /**
     * Legge byte dal canale NIO fino alla prima riga JSON terminata da `\n`.
     * La conversione UTF-8 avviene solo sulla riga completa, così caratteri
     * multibyte divisi fra read TCP non vengono corrotti.
     */
    private Response readLine() throws IOException {
        while (true) {
            if (!pendingLines.isEmpty())
                return GSON.fromJson(pendingLines.remove(), Response.class);
            netBuff.clear(); // per scrivere nel buff
            int n = chan.read(netBuff);
            if (n < 0)
                throw new IOException("server chiuso la connessione");
            netBuff.flip(); // per leggere dal buff
            while (netBuff.hasRemaining()) {
                byte b = netBuff.get();
                if (b == '\n') {
                    pendingLines.add(new String(lineBytes.toByteArray(), StandardCharsets.UTF_8));
                    lineBytes.reset();
                } else {
                    if (lineBytes.size() == BUF_CAP)
                        throw new IOException("risposta oltre " + BUF_CAP + " byte senza newline");
                    lineBytes.write(b);
                }
            }
        }
    }

    // Chiude il canale TCP NIO.
    @Override
    public void close() throws IOException { chan.close(); }
}
