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
import java.util.Arrays;

/**
 * Loop CLI: legge comandi da stdin, costruisce la Request JSON, invia via
 * ClientConn e renderizza la response. Stateless per-call: lo stato del gioco
 * è sul server (la CLI lo ridisegna ad ogni risposta).
 */
public class Cli {

    private static final JsonArray EMPTY = new JsonArray();

    private final ClientConn conn;
    private final int udpPort;

    public Cli(ClientConn conn, int udpPort) {
        this.conn = conn;
        this.udpPort = udpPort;
    }

    public static void printHelp() {
        System.out.println(
            "Comandi:\n" +
            "  register <user> <psw>\n" +
            "  update   <oldU> <oldPsw> [newU|-] [newPsw|-]\n" +
            "  login    <user> <psw>\n" +
            "  logout\n" +
            "  submit   <w1> <w2> <w3> <w4>\n" +
            "  info     [roundId]      partita corrente = -1 (default)\n" +
            "  stats    [roundId]\n" +
            "  leaders  [ -k N | -name X ]   default = tutti\n" +
            "  me       statistiche personali\n" +
            "  quit / exit\n" + 
            "  help\n");
    }

    public void run() throws IOException {
        BufferedReader in = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) { // legge user input
            if (!dispatch(line.trim())) 
                break;
        }
    }

    private boolean dispatch(String line) throws IOException {
        if (line.isEmpty()) 
            return true;
        String[] t = line.split("\\s+"); // spazi bianchi consecutivi (spazio, tab, newline, ecc.)
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
        sendAndRender(r);
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
        Request r = req("login");
        r.username = t[1]; 
        r.psw = t[2];
        r.udpPort = udpPort; // porta UDP per notif async
        sendAndRender(r);
    }

    private void cmdLogout(String[] t) {
        Request r = req("logout"); 
        sendAndRender(r); 
    }

    private void cmdSubmit(String[] t) {
        if (t.length < 5) {
            System.out.println("uso: submit <w1> <w2> <w3> <w4>"); 
            return; 
        }
        Request r = req("submitProposal");
        r.words = Arrays.asList(t[1], t[2], t[3], t[4]);
        sendAndRender(r);
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
    private void sendAndRender(Request r) {
        try {
            Response res = conn.sendAndRetreive(r);
            if ("ERROR".equals(res.status)) {
                System.out.println("ERRORE [" + res.errorCode + "]: " + res.message);
                return;
            }
            render(r.operation, res.payload);
        } catch (IOException e) {
            System.out.println("[client] errore connessione: " + e.getMessage());
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
            case "submitProposal":
                System.out.println(">>> " + payload.get("result").getAsString());
                renderGameInfo(payload.getAsJsonObject("game"));
                break;
            default: 
                System.out.println("OK"); // register/logout/update
        }
    }

    // render di una partita (live o storico). Condiviso con UdpClient.
    public static void renderGameInfo(JsonObject o) {
        if (o == null) { 
            System.out.println("  (nessuna info)"); 
            return; 
        }
        if (o.has("gameId"))
            System.out.println("partita round " + o.get("gameId").getAsInt());
        if (o.has("sourceGameId")) // sarebbe meglio non comunicarlo al giocatore (per scopo didattico si lascia)
            System.out.println("  source game " + o.get("sourceGameId").getAsInt());
        if (o.has("remainingSec"))
            System.out.println("  tempo rimanente: " + o.get("remainingSec").getAsInt() + "s");
        if (o.has("finished") && o.get("finished").getAsBoolean())
            System.out.println("  stato: FINITA - esito: " + 
                (o.has("outcome") ? o.get("outcome").getAsString() : "(in attesa di storico)"));
        if (o.has("correct")) 
            System.out.println("  corrette:  " + o.get("correct").getAsInt());
        if (o.has("errors"))
            System.out.println("  errori:    " + o.get("errors").getAsInt());
        if (o.has("score"))
            System.out.println("  punteggio: " + o.get("score").getAsInt());
        if (o.has("remainingWords")) {
            JsonArray rem = o.getAsJsonArray("remainingWords");
            System.out.println("  -- parole rimaste --");
            System.out.print("   ");
            for (JsonElement w : rem) System.out.print(w.getAsString() + "   ");
            System.out.println();
        }
        if (o.has("assignment")) {
            System.out.println("  -- soluzione --");
            for (JsonElement g : o.getAsJsonArray("assignment")) {
                JsonObject grp = g.getAsJsonObject();
                System.out.print("   [" + grp.get("theme").getAsString() + "] ");
                for (JsonElement w : grp.getAsJsonArray("words")) System.out.print(w.getAsString() + ", ");
                System.out.println();
            }
        }
    }

    private static void renderGameStats(JsonObject o) {
        if (o == null) { 
            System.out.println("  (nessuna statistica)"); 
            return; 
        }
        System.out.println("partecipanti: " + o.get("participantsTotal").getAsInt());
        if (o.has("inProgress"))
            System.out.println("  in corso:  " + o.get("inProgress").getAsInt());
        if (o.has("avgScore"))
            System.out.println("  media:     " + o.get("avgScore").getAsInt());
        System.out.println("  finiti:    " + o.get("finished").getAsInt() + 
            " (vinti " + o.get("won").getAsInt() + ")");
    }

    private static void renderLeaderboard(JsonObject o) {
        System.out.println("-- classifica --");
        int i = 1;
        for (JsonElement e : o.getAsJsonArray("leaderboard")) {
            JsonObject row = e.getAsJsonObject();
            System.out.printf("%2d. %-20s %6d%n", i++, row.get("username").getAsString(), 
                row.get("cumulativeScore").getAsInt());
        }
        if (o.has("playerRank"))
            System.out.println("    rango richiesto: #" + o.get("playerRank").getAsInt());
    }

    private static void renderPlayerStats(JsonObject o) {
        System.out.println("-- statistiche personali --");
        System.out.println("  Puzzles completed:  " + o.get("puzzlesCompleted").getAsInt());
        System.out.println("  Win rate %:         " + o.get("winRate").getAsInt() + "%");
        System.out.println("  Loss rate %:        " + o.get("lossRate").getAsInt() + "%");
        System.out.println("  Current streak:     " + o.get("currentStreak").getAsInt());
        System.out.println("  Max streak:         " + o.get("maxStreak").getAsInt());
        System.out.println("  Perfect puzzles:    " + o.get("perfectPuzzles").getAsInt());
        System.out.print("  Mistake histogram:  ");
        for (JsonElement v : o.getAsJsonArray("mistakeHistogram")) System.out.print(v.getAsInt() + "  ");
        System.out.println();
    }
}
