package server.loader;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
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
     * il reader viene riaperto e il ciclo ricomincia. Su un oggetto illeggibile
     * si riapre e si riprende dal successivo (mai spin/loop infinito).
     */
    public final class CyclicGameIterator implements Iterator<GameData> {
        private JsonReader reader;
        private boolean open = false;
        private int index = 0; // oggetti già emessi con successo (per riallineare dopo un errore)

        private void reopen() throws IOException {
            if (open) { 
                try { reader.close(); } 
                catch (IOException ignored) {} 
            }
            reader = open();
            try {
                reader.beginArray();
            } catch (IOException | RuntimeException e) {
                // Se beginArray fallisce, la chain appena aperta va richiusa:
                // altrimenti quest'FD resta bloccato e `open` (false) farebbe
                // riaprire un'altra stream alla prossima next() -> leak per errore.
                reader.close();
                throw e;
            }
            open = true;
        }

        private void ensureOpen() throws IOException { if (!open) reopen(); }

        @Override
        public boolean hasNext() { return true; } // ciclico: sempre vero

        // Robusto: in caso di JSON malformato, non si pianta.
        // "Salta l'errore" e riprova subito dopo.
        @Override
        public GameData next() {
            while (true) {
                try {
                    ensureOpen();
                    if (!reader.hasNext()) { 
                        reopen(); 
                        index = 0; 
                    } // wrap ciclico a EOF
                    GameData g = GSON.fromJson(reader, GameData.class);
                    index++;
                    return g;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (JsonSyntaxException e) {
                    // reader desincronizzato sul token rotto: riapri e salta oltre.
                    System.err.println("[GameLoader] partita malformata saltata: " + e.getMessage());
                    try {
                        reopen();
                        for (int i = 0; i <= index; i++) reader.skipValue(); // buoni (index) + rotto (1)
                        index++; // riprende da quello successivo
                    } catch (IOException io) { throw new UncheckedIOException(io); }
                }
            }
        }
    }

    public CyclicGameIterator cyclicIterator() {
        return new CyclicGameIterator();
    }

    // Apre il file come JsonReader UTF-8 (streaming, non bufferizzato in RAM).
    private JsonReader open() throws IOException {
        return new JsonReader(
                new InputStreamReader(
                    new FileInputStream(path), StandardCharsets.UTF_8));
    }
}
