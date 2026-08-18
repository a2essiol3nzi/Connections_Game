package server.loader;

import com.google.gson.Gson;
import server.model.GameData;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Caricatore PIGO delle partite (file fornito dai docenti).
 *
 * PROBLEMA RISOLTO: né il Data Binding (fromJson→array, tutto in RAM) né uno
 * streaming che comunque accumulava tutto in una List tenevano il file "leggero".
 * Qui le partite NON stanno mai tutte in RAM:
 *   • all'avvio si INDICCIZZANO gli "span" (offset inizio/fine) di ogni oggetto
 *     partita tramite una scansione byte-per-byte del file con RandomAccessFile
 *     (I/O NON bufferizzato → la posizione su file è esatta);
 *   • gameAt(i) legge SOLO i byte di quell'oggetto e lo parsia con Gson
 *     (Data Binding limitato a UN oggetto → memoria costante);
 *   • stream() espone un generatore lazy (Stream<GameData>) una partita alla volta.
 *
 * Scansione: il file è `[ {…}, {…}, … ]`. Cerchiamo ogni oggetto top-level
 * bilanciando le parentesi graffe `{ }` (ignorando quelle dentro stringhe).
 */
public class GameLoader {

    private static final Gson GSON = new Gson();

    /** Span (in byte) di ogni oggetto-partita nel file. */
    private static final class Span { final long start, end; Span(long s, long e){start=s;end=e;} }

    private final List<Span> spans = new ArrayList<>();
    private final String path;
    private final int total;

    public GameLoader(String path) throws IOException {
        this.path = path;
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            long len = raf.length();
            // trova '[' iniziale
            int c = skipWs(raf);
            if (c != '[') throw new IOException("file partite non è un array JSON");
            long pos = raf.getFilePointer();
            while (pos < len) {
                c = raf.read();
                if (c == -1) break;
                if (c == ']') break;            // fine array
                if (c == ',') { pos = raf.getFilePointer(); continue; }
                if (c == '{') {                 // inizio oggetto-partita
                    long start = raf.getFilePointer() - 1; // includi '{'
                    long end = scanObject(raf);            // ritorna pos dopo '}'
                    spans.add(new Span(start, end));
                    pos = raf.getFilePointer();
                } else {
                    pos = raf.getFilePointer();
                }
            }
        }
        total = spans.size();
    }

    /** Numero di partite disponibili. */
    public int total() { return total; }

    /** Carica (on-demand) la partita all'indice i parsandone UNA sola. */
    public GameData gameAt(int i) throws IOException {
        if (i < 0 || i >= total)
            throw new IndexOutOfBoundsException("game index " + i + " / " + total);
        Span s = spans.get(i);
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            raf.seek(s.start);
            int n = (int) (s.end - s.start);
            byte[] buf = new byte[n];
            raf.readFully(buf);
            return GSON.fromJson(new String(buf, StandardCharsets.UTF_8), GameData.class);
        }
    }

    /** Generatore lazy: una partita alla volta, su richiesta. */
    public Stream<GameData> stream() {
        return Stream.iterate(0, n -> n + 1)
                .limit(total)
                .map(i -> { try { return gameAt(i); }
                            catch (IOException e) { throw new java.io.UncheckedIOException(e); } });
    }

    // ---- supporto scansione ----

    /** Legge caratteri ignorando gli spazi bianchi; ritorna il primo non-spazio. */
    private static int skipWs(RandomAccessFile raf) throws IOException {
        int c;
        while ((c = raf.read()) != -1) {
            if (!Character.isWhitespace(c)) return c;
        }
        return -1;
    }

    /**
     * Dato che il cursore è su '{', scorre fino alla '}' di chiusura bilanciando
     * le parentesi e ignorando quelle dentro le stringhe. Ritorna la posizione
     * (esclusiva) subito DOPO la '}'.
     */
    private static long scanObject(RandomAccessFile raf) throws IOException {
        int depth = 1;
        boolean inStr = false;
        int c;
        while ((c = raf.read()) != -1) {
            if (inStr) {
                if (c == '\\') { raf.read(); continue; } // escape: salta il successivo
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return raf.getFilePointer(); }
        }
        throw new IOException("parentesi non bilanciate nel file partite");
    }
}
