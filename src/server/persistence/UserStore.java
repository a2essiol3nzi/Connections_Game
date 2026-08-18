package server.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Store degli UTENTI: registrazione, login, aggiornamento credenziali,
 * statistiche e persistenza su file JSON.
 *
 * ── ID UNIVOCO IMMUTABILE (consiglio prof.) ──
 * Ogni utente ha un `id` intero univoco e IMMUTABILE, assegnato all'atto
 * della registrazione. La mappa PRIMARIA è <id, User>: la sua struttura NON
 * cambia mai (la chiave id non varia). L'indice secondario `nameToId`
 * (<username, id>) è l'unico a cambiare in caso di rinomina. Così le
 * modifiche di credenziali non alterano la struttura della mappa principale
 * né di altre strutture che referenziano l'utente per id.
 *
 * ── SEMPLIFICAZIONE PASSWORD ──
 * Salvate IN CHIARO (scelta consapevole: il progetto non focalizza la
 * sicurezza). In produzione usare un hash. Non copiare in contesti reali.
 *
 * ── CONCORRENZA (senza lock globale) ──
 *   • byId è ConcurrentHashMap (operazioni mappa thread-safe);
 *   • ogni operazione su un account avviene sotto `synchronized(u)` sull'oggetto
 *     User → due utenti diversi NON si bloccano (il ConcurrentHashMap protegge
 *     solo le operazioni di mappa, non i campi dentro l'oggetto User);
 *   • `nextId` è un AtomicInteger (assegnazione id thread-safe);
 *   • la rinomina tocca solo `nameToId` (putIfAbsent + remove) → nessun lock
 *     strutturale necessario, ma è serializzata perché rara.
 * Rispetta il vincolo: solo synchronized/wait/notify/Atomic*, NO locks.*.
 *
 * ── PERSISTENZA ──
 * Utenti in RAM (pochi, accessi frequenti); snapshot JSON periodico +
 * atomico (temp + rename). Alternativa più efficiente a larga scala = DB
 * embedded, ma over-engineering qui.
 */
public class UserStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Mappa primaria: id immutabile -> User (struttura mai modificata da rename).
    private final ConcurrentHashMap<Integer, User> byId = new ConcurrentHashMap<>();
    // Indice secondario: username -> id (cambia solo alla rinomina).
    private final ConcurrentHashMap<String, Integer> nameToId = new ConcurrentHashMap<>();
    // Generatore di id univoci e monotoni.
    private final AtomicInteger nextId = new AtomicInteger(1);

    private final String persistFile;

    public UserStore(String persistFile) {
        this.persistFile = persistFile;
        load();
    }

    /** Record di un singolo utente. `id` è immutabile dopo la creazione. */
    public static final class User {
        public int id;                 // immutabile
        public String username;       // può cambiare (solo campo + indice)
        public String password;       // IN CHIARO (vedi nota in testa)

        public int cumulativeScore;   // punteggio totale → classifica
        public int puzzlesPlayed;
        public int puzzlesWon;
        public int puzzlesLost;
        public int notFinished;
        public int currentStreak;
        public int maxStreak;
        public int perfectPuzzles;
        public int[] mistakeHist = new int[6]; // [0..3] vinti con 0..3 err, [4] persi, [5] no-time
    }

    // ---- REGISTRAZIONE ----
    // Ritorna null in caso di successo, altrimenti un codice di errore.
    public String register(String username, String password) {
        if (username == null || password == null || username.isBlank())
            return "ERR_INVALID";
        if (nameToId.containsKey(username))
            return "ERR_USERNAME_TAKEN";
        User u = new User();
        u.id = nextId.getAndIncrement();
        u.username = username;
        u.password = password;
        byId.put(u.id, u);
        nameToId.put(username, u.id);  // put non-atomico con byId.put ma seriale in register
        return null; // OK
    }

    // ---- LOGIN ----
    // Risolve l'id, prendi l'User, controlla la password sotto synchronized(u).
    // Ritorna null in caso di successo, altrimenti un codice di errore.
    public String login(String username, String password) {
        Integer id = nameToId.get(username);
        if (id == null) return "ERR_USER_NOT_FOUND";
        User u = byId.get(id);
        synchronized (u) {
            if (!password.equals(u.password))
                return "ERR_WRONG_PASSWORD";
        }
        return null; // OK
    }

    // ---- AGGIORNAMENTO CREDENZIALI ----
    // oldPsw obbligatoria; newUsername e/o newPsw opzionali.
    // Ritorna null in caso di successo, altrimenti un codice di errore.
    public String updateCredentials(String oldUsername, String oldPsw,
                                    String newUsername, String newPsw) {
        Integer id = nameToId.get(oldUsername);
        if (id == null) return "ERR_USER_NOT_FOUND";
        User u = byId.get(id);
        synchronized (u) {
            if (!oldPsw.equals(u.password))
                return "ERR_WRONG_PASSWORD";
            if (newPsw != null) u.password = newPsw;
            if (newUsername != null && !newUsername.equals(oldUsername)) {
                // rinomina: solo l'INDICE secondario cambia; byId resta invariato.
                if (nameToId.putIfAbsent(newUsername, id) != null)
                    return "ERR_USERNAME_TAKEN";
                nameToId.remove(oldUsername);
                u.username = newUsername;
            }
        }
        return null; // OK
    }

    /** Tutti gli utenti (per classifica). */
    public List<User> allUsers() {
        return new ArrayList<>(byId.values());
    }

    /** Presenza utente per nome. */
    public boolean hasUser(String username) {
        return nameToId.containsKey(username);
    }

    /** Lookup per nome → User (o null). */
    public User getByName(String username) {
        Integer id = nameToId.get(username);
        return id == null ? null : byId.get(id);
    }

    // ---- PERSISTENZA (snapshot JSON atomico) ----
    public void persist() throws IOException {
        JsonArray arr = new JsonArray();
        for (User u : byId.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", u.id);
            o.addProperty("username", u.username);
            o.addProperty("password", u.password); // IN CHIARO
            o.addProperty("cumulativeScore", u.cumulativeScore);
            o.addProperty("puzzlesPlayed", u.puzzlesPlayed);
            o.addProperty("puzzlesWon", u.puzzlesWon);
            o.addProperty("puzzlesLost", u.puzzlesLost);
            o.addProperty("notFinished", u.notFinished);
            o.addProperty("currentStreak", u.currentStreak);
            o.addProperty("maxStreak", u.maxStreak);
            o.addProperty("perfectPuzzles", u.perfectPuzzles);
            JsonArray m = new JsonArray();
            for (int x : u.mistakeHist) m.add(x);
            o.add("mistakeHist", m);
            arr.add(o);
        }
        Path p = Path.of(persistFile);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Path tmp = Path.of(persistFile + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(arr, w);
        }
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- CARICAMENTO (all'avvio) ----
    // File utenti (piccolo) → leggibile interamente. Ripristina id e nextId.
    private void load() {
        Path p = Path.of(persistFile);
        if (!Files.exists(p)) return;
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            JsonArray arr = GSON.fromJson(r, JsonArray.class);
            if (arr == null) return;
            int maxId = 0;
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                User u = new User();
                u.id = o.get("id").getAsInt();
                u.username = o.get("username").getAsString();
                u.password = o.get("password").getAsString();
                u.cumulativeScore = o.get("cumulativeScore").getAsInt();
                u.puzzlesPlayed = o.get("puzzlesPlayed").getAsInt();
                u.puzzlesWon = o.get("puzzlesWon").getAsInt();
                u.puzzlesLost = o.get("puzzlesLost").getAsInt();
                u.notFinished = o.get("notFinished").getAsInt();
                u.currentStreak = o.get("currentStreak").getAsInt();
                u.maxStreak = o.get("maxStreak").getAsInt();
                u.perfectPuzzles = o.get("perfectPuzzles").getAsInt();
                JsonArray m = o.getAsJsonArray("mistakeHist");
                for (int k = 0; k < 6 && k < m.size(); k++)
                    u.mistakeHist[k] = m.get(k).getAsInt();
                byId.put(u.id, u);
                nameToId.put(u.username, u.id);
                maxId = Math.max(maxId, u.id);
            }
            nextId.set(maxId + 1); // riprendi da dove eravamo
        } catch (IOException e) {
            System.err.println("[UserStore] load fallito, parto vuoto: " + e.getMessage());
        }
    }
}
