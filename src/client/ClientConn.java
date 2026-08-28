package client;

import com.google.gson.Gson;
import protocol.Request;
import protocol.Response;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

/**
 * Connessione TCP persistente lato client in NIO (SocketChannel + Selector).
 *
 * Il server usa una riga JSON per ogni risposta (`\n`). Le chiamate sono
 * serializzate (synchronized): un unico lock copre scrivi+leggi, così il thread
 * CLI principale e l'eventuale fetch dall'UdpClient non intrecciano le righe.
 */
public class ClientConn implements AutoCloseable {

    private static final int BUF_CAP = 65536;
    private static final Gson GSON = new Gson();

    private final SocketChannel chan; // canale TCP non bloccante
    private final Selector sel;
    private final SelectionKey key;
    private final ByteBuffer netBuff = ByteBuffer.allocate(BUF_CAP); // buffer di lettura, 64KiB
    private final StringBuilder lineBuf = new StringBuilder(); // accumulatore righe a cavallo di più read

    public ClientConn(String host, int port) throws IOException {
        sel = Selector.open();
        chan = SocketChannel.open();
        chan.configureBlocking(false); // attivazione conf non bloccante
        // Si attende fino a che la connessione TCP non è del tutto instaurata
        if (!chan.connect(new InetSocketAddress(host, port))) {
            chan.register(sel, SelectionKey.OP_CONNECT);
            while (!chan.finishConnect())
                sel.select();
        }
        key = chan.register(sel, SelectionKey.OP_READ);
    }

    /**
     * Invia una richiesta e legge la riga-risposta corrispondente.
     * Scrive e legge in un'unica regione critica, quindi il request/response 
     * è atomico rispetto agli altri chiamanti (thread CLI + fetch UDP su stesso). 
     * Senza questo lock due chiamate concorrenti mescolerebbero le righe JSON sul canale.
     */
    public synchronized Response sendAndRetreive(Request req) throws IOException {
        writeAll(ByteBuffer.wrap((GSON.toJson(req) + "\n").getBytes(StandardCharsets.UTF_8)));
        return readLine();
    }

    /**
     * Scrive tutto il buffer sul canale non bloccante.
     * Se il write non riesce a scrivere, si fa segnalare dal Selector
     * quando il canale è scrivibile, poi si toglie l'interesse WRITE e si riprende.
     *
     * NOTA: non si lascia mai l'interesse WRITE: un SocketChannel è (quasi) sempre
     * "scrivibile" (send buffer del kernel con spazio), quindi un `select()`
     * rientrerebbe subito anche senza dati in arrivo; la lettura in `readLine()`
     * andrebbe in busy-spin (100% CPU) invece di attendere la risposta del server.
     */
    private void writeAll(ByteBuffer w) throws IOException {
        while (w.hasRemaining()) {
            if (chan.write(w) == 0) {
                key.interestOps(SelectionKey.OP_WRITE);
                sel.select();
                key.interestOps(SelectionKey.OP_READ);
            }
        }
    }

    /**
     * Legge dal canale finché nel buffer di ricostruzione non compare un `\n`.
     * Poiché il canale è non bloccante, i singoli read possono restituire 0 (niente
     * ancora disponibile) o frammenti parziali di riga: questi vengono accumulati in
     * lineBuf tra una chiamata e la successiva.
     */
    private Response readLine() throws IOException {
        while (true) {
            int nl = lineBuf.indexOf("\n");
            if (nl >= 0) {
                String line = lineBuf.substring(0, nl);
                lineBuf.delete(0, nl + 1);
                return GSON.fromJson(line, Response.class);
            }
            netBuff.clear(); // per scrivere nel buff
            int n = chan.read(netBuff);
            if (n < 0)
                throw new IOException("server chiusa la connessione");
            if (n == 0) {
                sel.select(); // aspetta che il canale sia leggibile
                continue;
            }
            netBuff.flip(); // per leggere dal buff
            byte[] b = new byte[netBuff.remaining()];
            netBuff.get(b);
            lineBuf.append(new String(b, StandardCharsets.UTF_8));
        }
    }

    // Chiude il canale e il selettore.
    @Override
    public void close() throws IOException { chan.close(); sel.close(); }
}
