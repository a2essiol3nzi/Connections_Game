package server.loader;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import server.model.GameData;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Caricatore PIGRO delle partite (file fornito dai docenti).
 *
 * Approccio "generatore"/iteratore: le partite NON stanno mai tutte in RAM.
 *   - all'avvio si conta il numero di oggetti con UNA sola passata sequenziale
 *     (JsonReader + skipValue);
 *   - cyclicIterator() espone un iteratore lazy che legge UN oggetto alla volta
 *     via JsonReader e, arrivato in fondo, riapre il file e ricomincia
 *     (ciclo infinito: una partita dopo l'altra, nessuna in RAM).
 *
 * Memoria O(1) rispetto ai corpi delle partite: Gson fa Data Binding di un solo
 * oggetto per volta; il file non viene mai letto tutto insieme.
 */
public class GameLoader {

    private static final Gson GSON = new Gson();

    private final String path;
    private final int total;

    public GameLoader(String path) throws IOException {
        this.path = path;
        this.total = countGames();
    }

    // Numero di partite disponibili (contate a costruzione).
    public int total() { return total; }

    /**
     * Conta gli oggetti di primo livello con una passata sequenziale.
     * skipValue() consuma un intero elemento dell'array (bilanciato da Gson anche
     * se annidato), quindi conta SOLO gli oggetti di primo livello.
     */
    private int countGames() throws IOException {
        try (JsonReader r = open()) {
            r.beginArray();
            int n = 0;
            while (r.hasNext()) { r.skipValue(); n++; }
            return n;
        }
    }

    /**
     * Generatore lazy: scorre gli oggetti uno a uno, ciclico all'infinito.
     * Ogni next() parsa UN solo oggetto (memoria costante); alla fine del file
     * il reader viene riaperto e il ciclo ricomincia.
     */
    public Iterator<GameData> cyclicIterator() {
        return new Iterator<>() {
            private JsonReader reader;
            private boolean open = false;

            private void ensureOpen() {
                if (open) 
                    return;
                try {
                    reader = open();
                    reader.beginArray();
                    open = true;
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }

            public boolean hasNext() { return true; } // ciclico: sempre vero

            public GameData next() {
                ensureOpen();
                try {
                    if (!reader.hasNext()) { // fine file -> ricomincia
                        reader.close();
                        reader = open();
                        reader.beginArray();
                    }
                    return GSON.fromJson(reader, GameData.class);
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        };
    }

    // Apre il file come JsonReader UTF-8 (streaming, non bufferizzato in RAM).
    private JsonReader open() throws IOException {
        return new JsonReader(
                new InputStreamReader(
                    new FileInputStream(path), StandardCharsets.UTF_8));
    }
}
