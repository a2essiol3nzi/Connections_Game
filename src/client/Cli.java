package client;

import com.google.gson.Gson;
import protocol.Request;
import protocol.Response;
import protocol.payload.GameInfoPayload;
import protocol.payload.GameStatsPayload;
import protocol.payload.GroupPayload;
import protocol.payload.LeaderboardPayload;
import protocol.payload.PlayerStatsPayload;
import protocol.payload.SubmitPayload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    // Gson per riconvertire il payload nel POJO di destinazione.
    private static final Gson GSON = new Gson();

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
        if (res != null && "OK".equals(res.status) && res.payload != null) {
            SubmitPayload sp = payload(res, SubmitPayload.class);
            if (sp.game != null) {
                boolean won = sp.game.correct != null && sp.game.correct >= 3;
                boolean lost = sp.game.errors != null && sp.game.errors >= 4;
                if (won || lost)
                    sendAndRender(req("requestGameStats"));
            }
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
            render(r.operation, res);
            return res;
        } catch (IOException e) {
            System.out.println("[client] errore connessione: " + e.getMessage());
            return null;
        }
    }

    // Riconverte il payload (Object deserializzato dal wire) nel POJO dell'operazione.
    private static <T> T payload(Response res, Class<T> cls) {
        return GSON.fromJson(GSON.toJson(res.payload), cls);
    }

    private static void render(String op, Response res) {
        switch (op) {
            case "login": case "requestGameInfo": 
                renderGameInfo(payload(res, GameInfoPayload.class)); break;
            case "requestGameStats": 
                renderGameStats(payload(res, GameStatsPayload.class)); break;
            case "requestLeaderboard": 
                renderLeaderboard(payload(res, LeaderboardPayload.class)); break;
            case "requestPlayerStats": 
                renderPlayerStats(payload(res, PlayerStatsPayload.class)); break;
            case "submitProposal": {
                SubmitPayload sp = payload(res, SubmitPayload.class);
                String col = "CORRECT".equals(sp.result) ? GREEN : RED;
                System.out.println(col + BOLD + "  · " + sp.result + RESET);
                renderGameInfo(sp.game);
                break;
            }
            default: 
                System.out.println(GREEN + "OK" + RESET); // register/logout/update
        }
    }

    // Stesso sia per live che storico (condiviso con UdpClient).
    public static void renderGameInfo(GameInfoPayload o) {
        if (o == null) { 
            System.out.println(DIM + "  (nessuna info)" + RESET); 
            return; 
        }
        section("PARTITA");
        if (o.gameId != null) {
            System.out.print("  round " + CYAN + o.gameId + RESET);
            if (o.sourceGameId != null)
                System.out.print("   " + DIM + "(source " + o.sourceGameId + ")" + RESET);
            System.out.println();
        }
        if (o.remainingSec != null)
            System.out.println("  tempo rimanente: " + YELLOW + o.remainingSec + "s" + RESET);
        if (Boolean.TRUE.equals(o.finished)) {
            boolean won = o.correct != null && o.correct >= 3;
            boolean lost = o.errors != null && o.errors >= 4;
            if (won) {
                System.out.println("  🏆 " + BOLD + GREEN + "HAI VINTO! Hai trovato tutti i gruppi." + RESET);
            } else if (lost) {
                System.out.println("  ✖ " + BOLD + RED + "HAI PERSO: 4 errori." + RESET);
            } else {
                // tempo scaduto senza vittoria/sconfitta
                System.out.println("  ⏱ " + BOLD + YELLOW + "TEMPO SCADUTO — partita conclusa." + RESET);
            }
        }
        if (o.correct != null || o.errors != null || o.score != null) {
            StringBuilder st = new StringBuilder();
            if (o.correct != null) st.append(GREEN).append("corrette: ").append(o.correct).append(RESET).append("   ");
            if (o.errors != null) st.append(RED).append("errori: ").append(o.errors).append(RESET).append("   ");
            if (o.score != null) st.append(CYAN).append("punteggio: ").append(o.score).append(RESET);
            // NB: NIENTE .trim() sulla stringa: toglie anche l'ESC iniziale (U+001B<=U+0020) rompendo il colore.
            System.out.println("  " + st.toString().replaceAll(" +$", "")); // matcha uno o più spazi ( +) alla fine della stringa ($) e li rimuove
        }
        if (o.remainingWords != null && !o.remainingWords.isEmpty())
            renderBoard(o.remainingWords);
        if (o.assignment != null) {
            section("SOLUZIONE");
            for (GroupPayload grp : o.assignment) {
                StringBuilder ws = new StringBuilder();
                for (String w : grp.words) ws.append(w).append(", ");
                String trail = ws.toString();
                if (trail.endsWith(", ")) trail = trail.substring(0, trail.length() - 2);
                System.out.println("  " + CYAN + BOLD + grp.theme + RESET + ": " + trail);
            }
        }
    }

    private static void renderGameStats(GameStatsPayload o) {
        if (o == null) { 
            System.out.println(DIM + "  (nessuna statistica)" + RESET); 
            return; 
        }
        section("STATISTICHE PARTITA");
        if (o.remainingSec != null)
            System.out.println("  tempo rimanente: " + YELLOW + o.remainingSec + "s" + RESET);
        System.out.println("  partecipanti: " + CYAN + o.participantsTotal + RESET);
        if (o.inProgress != null)
            System.out.println("  in corso:     " + YELLOW + o.inProgress + RESET);
        if (o.avgScore != null)
            System.out.println("  media:        " + CYAN + o.avgScore + RESET);
        System.out.println("  finiti:       " + o.finished + "   " + GREEN + 
            "(vinti " + o.won + ")" + RESET);
    }

    private static void renderLeaderboard(LeaderboardPayload o) {
        section("CLASSIFICA");
        int i = 1;
        for (LeaderboardPayload.Row row : o.leaderboard) {
            String pre = i <= 3 ? YELLOW + BOLD : "";
            System.out.println("  " + pre + String.format("%2d. %-20s %6d", i, row.username, row.cumulativeScore) + RESET);
            i++;
        }
        if (o.playerRank != null)
            System.out.println("  rango richiesto: " + CYAN + "#" + o.playerRank + RESET);
    }

    private static void renderPlayerStats(PlayerStatsPayload o) {
        section("STATISTICHE PERSONALI");
        System.out.println("  Puzzles completed:  " + CYAN + o.puzzlesCompleted + RESET);
        System.out.println("  Win rate %:         " + GREEN + o.winRate + RESET + "%");
        System.out.println("  Loss rate %:        " + RED + o.lossRate + RESET + "%");
        System.out.println("  Current streak:     " + YELLOW + o.currentStreak + RESET);
        System.out.println("  Max streak:         " + YELLOW + o.maxStreak + RESET);
        System.out.println("  Perfect puzzles:    " + GREEN + o.perfectPuzzles + RESET);
        // Istogramma errori: [0..3] vinte con 0..3 errori, [4] perse (4 errori), [5] non finite.
        // Etichette chiare sotto i valori (altrimenti l'istogramma è incomprensibile).
        String[] labels = {" 0 err", " 1 err", " 2 err", " 3 err", "persa", "non fin" };
        System.out.println("  Mistake histogram:");
        StringBuilder row = new StringBuilder("      ");
        for (int x : o.mistakeHistogram)
            row.append(GREEN).append(pad(String.valueOf(x), 8)).append(RESET);
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

    private static void renderBoard(List<String> list) {
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
