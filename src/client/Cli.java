package client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import protocol.Request;
import protocol.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Loop CLI: legge comandi da stdin, costruisce la Request JSON, invia via
 * ClientConn e renderizza la response. Stateless per-call: lo stato del gioco
 * è sul server (la CLI lo ridisegna ad ogni risposta).
 *
 * RESA TUI: colori ANSI + cornici (box-drawing) e board a griglia, il tutto
 * solo su stdout di console.
 */
public class Cli {

    // Logica per TUI a fine file
    private static final String RESET  = "\u001b[0m";
    private static final String BOLD   = "\u001b[1m";
    private static final String DIM    = "\u001b[2m";
    private static final String CYAN   = "\u001b[36m";
    private static final String GREEN  = "\u001b[32m";
    private static final String RED    = "\u001b[31m";
    private static final String YELLOW = "\u001b[33m";

    private final ClientConn conn;
    private final int udpPort;

    private String loggedIn = null; // username di login (evita "login shadowing")

    public Cli(ClientConn conn, int udpPort) {
        this.conn = conn;
        this.udpPort = udpPort;
    }

    public static void printHelp() {
        section("AIUTO - COMANDI");
        System.out.println("  " + CYAN + "register" + RESET + " <user> <psw>              registra un nuovo utente\n"
            + "  " + CYAN + "update" + RESET + "   <oldU> <oldPsw> [newU|-] [newPsw|-]   aggiorna credenziali\n"
            + "  " + CYAN + "login" + RESET + "    <user> <psw>                accedi (entra nella partita)\n"
            + "  " + CYAN + "logout" + RESET + "                     esci dalla partita\n"
            + "  " + CYAN + "submit" + RESET + "   <w1> <w2> <w3> <w4>        invia una proposta di gruppo\n"
            + "  " + CYAN + "info" + RESET + "     [roundId]                 stato/esito partita (-1 = corrente)\n"
            + "  " + CYAN + "stats" + RESET + "    [roundId]                 statistiche partita\n"
            + "  " + CYAN + "leaders" + RESET + "  [ -k N | -name X ]         classifica (default: tutti)\n"
            + "  " + CYAN + "me" + RESET + "       statistiche personali\n"
            + "  " + CYAN + "quit" + RESET + "/" + CYAN + "exit" + RESET + "                   chiudi il client\n"
            + "  " + CYAN + "help" + RESET + "                     mostra questo aiuto\n");
    }

    public void run() throws IOException {
        BufferedReader in = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
        printHelp();
        String line;
        while (true) {
            System.out.print(CYAN + BOLD + ">> " + RESET);
            System.out.flush();
            line = in.readLine(); // legge user input
            if (line == null) break;
            if (!dispatch(line.trim()))
                break;
        }
        System.out.println(); // riga vuota prima di chiudere
    }

    private boolean dispatch(String line) throws IOException {
        if (line.isEmpty()) 
            return true;
        String[] t = line.split("\\s+"); // spazi bianchi consecutivi (spazio, tab, newline, ecc.)
        // Se già loggati, non si può né registrarsi né rifare login.
        if ((loggedIn != null) && (t[0].equals("register") || t[0].equals("login"))) {
            System.out.println(YELLOW + "Già loggato come '" + loggedIn + "' - fai prima logout." + RESET);
            return true;
        }
        switch (t[0]) {
            case "help": {
                printHelp(); 
                return true;
            }
            case "register": {
                cmdRegister(t); 
                return true;
            }
            case "update": {
                cmdUpdate(t); 
                return true;
            }
            case "login": {
                cmdLogin(t); 
                return true;
            }
            case "logout": { 
                cmdLogout(t);
                return true; 
            }
            case "submit": {
                cmdSubmit(t); 
                return true;
            }
            case "info": {
                cmdGameInfo(t); 
                return true; 
            }
            case "stats": {
                cmdGameStats(t);
                return true; 
            }
            case "leaders": {
                cmdLeaders(t); 
                return true;
            }
            case "me": { 
                cmdMe(t);
                return true; 
            }
            case "quit": case "exit": return false;
            default: {
                System.out.println("Comando sconosciuto; usa 'help'"); 
                return true;
            }
        }
    }

    private static Request req(String op) { 
        Request r = new Request(); 
        r.operation = op; 
        return r; 
    }

    private static Integer parseId(String[] t, int i) {
        if (t.length > i) { try { return Integer.parseInt(t[i]); } catch (NumberFormatException e) { } }
        return -1;
    }

    private void cmdRegister(String[] t) {
        if (t.length < 3) { 
            System.out.println("uso: register <user> <psw>"); 
            return; 
        }
        Request r = req("register"); 
        r.username = t[1]; 
        r.psw = t[2];
        Response res = sendAndRender(r);
        if (res != null && "OK".equals(res.status))
            doLogin(t[1], t[2]); // login automatico
    }

    private void cmdUpdate(String[] t) {
        if (t.length < 4) {
            System.out.println("uso: update <oldU> <oldPsw> [newU|-] [newPsw|-]"); 
            return; 
        }
        Request r = req("updateCredentials");
        r.oldUsername = t[1]; 
        r.oldPsw = t[2];
        if (t.length > 3 && !"-".equals(t[3])) 
            r.newUsername = t[3];
        if (t.length > 4 && !"-".equals(t[4])) 
            r.newPsw = t[4];
        sendAndRender(r);
    }

    private void cmdLogin(String[] t) {
        if (t.length < 3) {
            System.out.println("uso: login <user> <psw>"); 
            return; 
        }
        doLogin(t[1], t[2]);
    }

    // Invia la richiesta e segna il client come loggato se riuscito.
    private void doLogin(String user, String psw) {
        Request r = req("login");
        r.username = user;
        r.psw = psw;
        r.udpPort = udpPort; // porta UDP per notif async
        Response res = sendAndRender(r);
        if (res != null && "OK".equals(res.status))
            loggedIn = user;
    }

    private void cmdLogout(String[] t) {
        sendAndRender(req("requestGameInfo"));
        sendAndRender(req("requestGameStats"));
        Response res = sendAndRender(req("logout"));
        if (res != null && "OK".equals(res.status))
            loggedIn = null; // il client torna "anonimo" solo a logout eseguito
    }

    private void cmdSubmit(String[] t) {
        if (t.length < 5) {
            System.out.println("uso: submit <w1> <w2> <w3> <w4>"); 
            return; 
        }
        Request r = req("submitProposal");
        r.words = Arrays.asList(t[1], t[2], t[3], t[4]);
        Response res = sendAndRender(r);
        // in caso di partita finita (won/lost) si richiede anche Stats
        if (res != null && 
            "OK".equals(res.status) && 
            res.payload != null && 
            res.payload.has("game"))
        {
            JsonObject g = res.payload.getAsJsonObject("game");
            boolean won = g.has("correct") && g.get("correct").getAsInt() >= 3;
            boolean lost = g.has("errors") && g.get("errors").getAsInt() >= 4;
            if (won || lost)
                sendAndRender(req("requestGameStats"));
        }
    }

    private void cmdGameInfo(String[] t) {
        Request r = req("requestGameInfo");
        r.gameId = parseId(t, 1); 
        sendAndRender(r);
    }

    private void cmdGameStats(String[] t) {
        Request r = req("requestGameStats");
        r.gameId = parseId(t, 1); 
        sendAndRender(r);
    }

    private void cmdLeaders(String[] t) {
        Request r = req("requestLeaderboard");
        if (t.length > 1 && "-k".equals(t[1]) && t.length > 2) r.topPlayers = Integer.parseInt(t[2]);
        else if (t.length > 1 && "-name".equals(t[1]) && t.length > 2) r.playerName = t[2];
        // default: entrambi assenti -> classifica completa (topPlayers null)
        sendAndRender(r);
    }

    private void cmdMe(String[] t) {
        Request r = req("requestPlayerStats");
        sendAndRender(r);
    }

    // --- INVIO+RENDER ---
    private Response sendAndRender(Request r) {
        try {
            Response res = conn.sendAndRetreive(r);
            if ("ERROR".equals(res.status)) {
                System.out.println(RED + "✗ ERRORE [" + res.errorCode + "]: " + res.message + RESET);
                return res;
            }
            render(r.operation, res.payload);
            return res;
        } catch (IOException e) {
            System.out.println("[client] errore connessione: " + e.getMessage());
            return null;
        }
    }

    private static void render(String op, JsonObject payload) {
        switch (op) {
            case "login": case "requestGameInfo": 
                renderGameInfo(payload); break;
            case "requestGameStats": 
                renderGameStats(payload); break;
            case "requestLeaderboard": 
                renderLeaderboard(payload); break;
            case "requestPlayerStats": 
                renderPlayerStats(payload); break;
            case "submitProposal": {
                String res = payload.get("result").getAsString();
                String col = "CORRECT".equals(res) ? GREEN : RED;
                System.out.println(col + BOLD + "  · " + res + RESET);
                renderGameInfo(payload.getAsJsonObject("game"));
                break;
            }
            default: 
                System.out.println(GREEN + "OK" + RESET); // register/logout/update
        }
    }

    // render di una partita (live o storico). Condiviso con UdpClient.
    public static void renderGameInfo(JsonObject o) {
        if (o == null) { 
            System.out.println(DIM + "  (nessuna info)" + RESET); 
            return; 
        }
        section("PARTITA");
        if (o.has("gameId")) {
            System.out.print("  round " + CYAN + o.get("gameId").getAsInt() + RESET);
            if (o.has("sourceGameId"))
                System.out.print("   " + DIM + "(source " + o.get("sourceGameId").getAsInt() + ")" + RESET);
            System.out.println();
        }
        if (o.has("remainingSec"))
            System.out.println("  tempo rimanente: " + YELLOW + o.get("remainingSec").getAsInt() + "s" + RESET);
        if (o.has("finished") && o.get("finished").getAsBoolean()) {
            boolean won = o.has("correct") && o.get("correct").getAsInt() >= 3;
            boolean lost = o.has("errors") && o.get("errors").getAsInt() >= 4;
            if (won) {
                System.out.println("  🏆 " + BOLD + GREEN + "HAI VINTO! Hai trovato tutti i gruppi." + RESET);
            } else if (lost) {
                System.out.println("  ✖ " + BOLD + RED + "HAI PERSO: 4 errori." + RESET);
            } else {
                // tempo scaduto senza vittoria/sconfitta
                String scol = YELLOW;
                System.out.println("  ⏱ " + BOLD + scol + "TEMPO SCADUTO — partita conclusa." + RESET);
            }
        }
        if (o.has("correct") || o.has("errors") || o.has("score")) {
            StringBuilder st = new StringBuilder();
            if (o.has("correct")) st.append(GREEN).append("corrette: ").append(o.get("correct").getAsInt()).append(RESET).append("   ");
            if (o.has("errors"))  st.append(RED).append("errori: ").append(o.get("errors").getAsInt()).append(RESET).append("   ");
            if (o.has("score"))   st.append(CYAN).append("punteggio: ").append(o.get("score").getAsInt()).append(RESET);
            // NB: NIENTE .trim() sulla stringa: toglie anche l'ESC iniziale (U+001B<=U+0020)
            // spezzando il colore. Si costruisce senza spazi iniziali e si indentano qui.
            System.out.println("  " + st.toString().replaceAll(" +$", ""));
        }
        if (o.has("remainingWords"))
            renderBoard(o.getAsJsonArray("remainingWords"));
        if (o.has("assignment")) {
            section("SOLUZIONE");
            for (JsonElement g : o.getAsJsonArray("assignment")) {
                JsonObject grp = g.getAsJsonObject();
                StringBuilder ws = new StringBuilder();
                for (JsonElement w : grp.getAsJsonArray("words")) ws.append(w.getAsString()).append(", ");
                String trail = ws.toString();
                if (trail.endsWith(", ")) trail = trail.substring(0, trail.length() - 2);
                System.out.println("  " + CYAN + BOLD + grp.get("theme").getAsString() + RESET + ": " + trail);
            }
        }
    }

    private static void renderGameStats(JsonObject o) {
        if (o == null) { 
            System.out.println(DIM + "  (nessuna statistica)" + RESET); 
            return; 
        }
        section("STATISTICHE PARTITA");
        if (o.has("remainingSec"))
            System.out.println("  tempo rimanente: " + YELLOW + o.get("remainingSec").getAsInt() + "s" + RESET);
        System.out.println("  partecipanti: " + CYAN + o.get("participantsTotal").getAsInt() + RESET);
        if (o.has("inProgress"))
            System.out.println("  in corso:     " + YELLOW + o.get("inProgress").getAsInt() + RESET);
        if (o.has("avgScore"))
            System.out.println("  media:        " + CYAN + o.get("avgScore").getAsInt() + RESET);
        System.out.println("  finiti:       " + o.get("finished").getAsInt()
            + "   " + GREEN + "(vinti " + o.get("won").getAsInt() + ")" + RESET);
    }

    private static void renderLeaderboard(JsonObject o) {
        section("CLASSIFICA");
        int i = 1;
        for (JsonElement e : o.getAsJsonArray("leaderboard")) {
            JsonObject row = e.getAsJsonObject();
            String name = row.get("username").getAsString();
            int pts = row.get("cumulativeScore").getAsInt();
            String pre = i <= 3 ? YELLOW + BOLD : "";
            System.out.println("  " + pre + String.format("%2d. %-20s %6d", i, name, pts) + RESET);
            i++;
        }
        if (o.has("playerRank"))
            System.out.println("  rango richiesto: " + CYAN + "#" + o.get("playerRank").getAsInt() + RESET);
    }

    private static void renderPlayerStats(JsonObject o) {
        section("STATISTICHE PERSONALI");
        System.out.println("  Puzzles completed:  " + CYAN + o.get("puzzlesCompleted").getAsInt() + RESET);
        System.out.println("  Win rate %:         " + GREEN + o.get("winRate").getAsInt() + RESET + "%");
        System.out.println("  Loss rate %:        " + RED + o.get("lossRate").getAsInt() + RESET + "%");
        System.out.println("  Current streak:     " + YELLOW + o.get("currentStreak").getAsInt() + RESET);
        System.out.println("  Max streak:         " + YELLOW + o.get("maxStreak").getAsInt() + RESET);
        System.out.println("  Perfect puzzles:    " + GREEN + o.get("perfectPuzzles").getAsInt() + RESET);
        // Istogramma errori: [0..3] vinte con 0..3 errori, [4] perse (4 errori), [5] non finite.
        // Etichette chiare sotto i valori (altrimenti l'istogramma è incomprensibile).
        JsonArray h = o.getAsJsonArray("mistakeHistogram");
        String[] labels = {" 0 err", " 1 err", " 2 err", " 3 err", "persa", "non fin" };
        System.out.println("  Mistake histogram:");
        StringBuilder row = new StringBuilder("      ");
        for (int i = 0; i < h.size(); i++)
            row.append(GREEN).append(pad(String.valueOf(h.get(i).getAsInt()), 8)).append(RESET);
        System.out.println(row.toString().trim());
        StringBuilder lab = new StringBuilder("      ");
        for (int i = 0; i < labels.length; i++)
            lab.append(DIM).append(pad(labels[i], 8)).append(RESET);
        System.out.println(lab.toString().trim());
    }

    // --- Metodi per stile TUI ---

    // Ripete un carattere n volte (String.repeat è Java 11+; qui target 8).
    private static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder(Math.max(0, n));
        for (int i = 0; i < n; i++) 
            sb.append(c);
        return sb.toString();
    }

    // Padding a destra fino a n caratteri.
    private static String pad(String s, int n) {
        int extra = n - s.length();
        return extra > 0 ? s + rep(' ', extra) : s;
    }

    // Intestazione di sezione in una cornice colorata.
    private static void section(String title) {
        String bar = rep('─', 58);
        System.out.println();
        System.out.println(CYAN + "┌" + bar + "┐" + RESET);
        System.out.println(CYAN + "│" + RESET + BOLD + CYAN + "  " + pad(title, 54) + "  " + RESET + CYAN + "│" + RESET);
        System.out.println(CYAN + "└" + bar + "┘" + RESET);
    }

    // Board dell'elenco parole (rimaste) disposto in una griglia colorata.
    private static void renderBoard(JsonArray rem) {
        List<String> list = new ArrayList<>();
        for (JsonElement e : rem) list.add(e.getAsString());
        if (list.isEmpty()) {
            System.out.println(DIM + "  (nessuna parola rimasta)" + RESET);
            return;
        }
        int maxLen = 0;
        for (String s : list) maxLen = Math.max(maxLen, s.length());
        int colW = Math.max(6, maxLen + 4); // ampiezza cella (spazi laterali inclusi)
        int cols = 4;
        int rows = (int) Math.ceil(list.size() / (double) cols);
        int innerW = cols * colW;
        System.out.println(CYAN + "┌" + rep('─', innerW + 2) + "┐" + RESET);
        for (int r = 0; r < rows; r++) {
            StringBuilder cell = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                String w = idx < list.size() ? list.get(idx) : "";
                cell.append(' ').append(pad(w, colW - 2)).append(' ');
            }
            System.out.println(CYAN + "│" + RESET + " " + cell + " " + CYAN + "│" + RESET);
        }
        System.out.println(CYAN + "└" + rep('─', innerW + 2) + "┘" + RESET);
    }
}
