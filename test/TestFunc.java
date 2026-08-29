import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import protocol.Response;
import protocol.payload.GameInfoPayload;
import protocol.payload.LeaderboardPayload;
import protocol.payload.PlayerStatsPayload;

import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Suit FUNZIONALE: tutte le 9 operazioni, codici di errore, gate di auth,
 * regola MALFORMATA-vs-ERRATA, case-insensitivity, confini payload.
 * Usa la prima partita caricata (source gameId 0) con le parole reali da games.json.
 */
public class TestFunc {

    private static final Gson GSON = new Gson();

    // Struttura di gioco dell'array JSON games.json (solo i campi che servono).
    static class Group { String theme; List<String> words; }
    static class Game { int gameId; List<Group> groups; }

    static List<List<String>> WORDS_GROUPS = new ArrayList<>();
    static List<String> G0, G1, G2, G3;
    static List<Game> GAMES; // cache games.json intero, per TestStats (rotation index)

    public static int run(int port) throws Exception {
        int fails = 0;
        loadWords(new java.io.File("data/games.json"));

        // ==== 1. REGISTER ====
        T.section("1. register");
        try (TC c = new TC(port, 40000)) {
            T.ok("register nuovo utente", c.register("t_user", "pw"));
            T.err("register stesso di nuovo", c.register("t_user", "pw"), "ERR_USERNAME_TAKEN");
            T.check("register senza psw", c.call(noPswReq("t_user2")), "ERROR", null, null);
            T.ok("login per check register-logged", c.login("t_user", "pw", 40000));
            T.err("register da loggato", c.register("t_x", "y"), "ERR_ALREADY_LOGGED_IN");
            T.ok("logout", c.logout());
        }

        // ==== 2. LOGIN / LOGOUT ====
        T.section("2. login / logout");
        try (TC c = new TC(port, 40000)) {
            // login senza udpPort non ha wrapper dedicato: campo null
            T.check("login senza udpPort", c.call(noUdpLoginReq("t_user", "pw")), "ERROR", "BAD_REQUEST", null);
            T.err("login psw errata", c.login("t_user", "sbagliata", 40000), "ERR_WRONG_PASSWORD");
            T.err("login utente inesistente", c.login("fantasma", "pw", 40000), "ERR_USER_NOT_FOUND");
            T.ok("login OK", c.login("t_user", "pw", 40000));
            T.err("login doppio stessa conn", c.login("t_user", "pw", 40000), "ERR_ALREADY_LOGGED_IN");
        }
        try (TC c2 = new TC(port, 40000)) {
            T.err("logout non loggato", c2.logout(), "ERR_NOT_LOGGED_IN");
        }

        // ==== 3. GATE AUTH ====
        T.section("3. operazioni protette senza login");
        try (TC c = new TC(port, 40000)) {
            T.err("submit senza login", c.submit(G0), "ERR_NOT_LOGGED_IN");
            T.err("gameInfo senza login", c.gameInfo(-1), "ERR_NOT_LOGGED_IN");
            T.err("gameStats senza login", c.gameStats(-1), "ERR_NOT_LOGGED_IN");
            T.err("leaderboard senza login", c.leaderboard(null, null), "ERR_NOT_LOGGED_IN");
            T.err("playerStats senza login", c.playerStats(), "ERR_NOT_LOGGED_IN");
        }

        // ==== 4. SUBMIT: MALFORMATA vs ERRATA (§2.2) ====
        T.section("4. submitProposal (MALFORMATA vs ERRATA)");
        List<String> WRONG = new ArrayList<>();
        WRONG.add(WORDS_GROUPS.get(0).get(0));
        WRONG.add(WORDS_GROUPS.get(1).get(0));
        WRONG.add(WORDS_GROUPS.get(2).get(0));
        WRONG.add(WORDS_GROUPS.get(3).get(0));
        List<String> BAD_WORD = new ArrayList<>(Arrays.asList("###NOT-A-WORD###", G0.get(0), G0.get(1), G0.get(2)));
        List<String> DUP = Arrays.asList(G0.get(0), G0.get(0), G0.get(1), G0.get(2));

        try (TC c = new TC(port, 40000)) {
            T.ok("login", c.login("t_user", "pw", 40000));
            GameInfoPayload p = c.asGameInfo(c.gameInfo(-1));
            T.cond("gameInfo iniziale: score=0", p.score == 0);
            // 4.1 errata: una parola per gruppo -> WRONG (-4)
            Response r = c.submit(WRONG);
            T.check("gruppo errato -> WRONG", r, "OK", null,
                    x -> "WRONG".equals(c.asSubmit(x).result));
            p = c.asGameInfo(c.gameInfo(-1));
            T.cond("dopo errata: errori=1 score=-4", p.errors == 1 && p.score == -4);
            // 4.2 malformata con parola fuori board: NON cambia stato
            T.err("parola fuori board -> MALFORMED", c.submit(BAD_WORD), "ERR_MALFORMED");
            p = c.asGameInfo(c.gameInfo(-1));
            T.cond("dopo malformata: invariato (1,-4)", p.errors == 1 && p.score == -4);
            // 4.3 duplicati -> MALFORMED
            T.err("proposta con duplicati -> MALFORMED", c.submit(DUP), "ERR_MALFORMED");
            // 4.4 gruppo corretto -> CORRECT (score = 6*1 - 4*1 = 2)
            r = c.submit(G0);
            T.check("gruppo 0 corretto -> CORRECT", r, "OK", null,
                    x -> "CORRECT".equals(c.asSubmit(x).result));
            p = c.asGameInfo(c.gameInfo(-1));
            T.cond("dopo corretto: correct=1 score=2", p.correct == 1 && p.score == 2);
            // 4.5 case-insensitive: gruppo 1 in minuscolo
            List<String> low = new ArrayList<>();
            for (String w : WORDS_GROUPS.get(1)) low.add(w.toLowerCase());
            r = c.submit(low);
            T.check("gruppo minuscole -> CORRECT", r, "OK", null,
                    x -> "CORRECT".equals(c.asSubmit(x).result));
            // 4.6 vincita: completa gruppo 2
            r = c.submit(WORDS_GROUPS.get(2));
            T.check("gruppo 2 -> CORRECT (vittoria)", r, "OK", null,
                    x -> "CORRECT".equals(c.asSubmit(x).result));
            p = c.asGameInfo(c.gameInfo(-1));
            T.cond("dopo vittoria: correct=3 finished", p.correct == 3 && Boolean.TRUE.equals(p.finished));
            // 4.7 submit dopo vittoria -> GAME_OVER_FOR_YOU
            T.err("submit dopo vittoria", c.submit(WORDS_GROUPS.get(3)), "ERR_GAME_OVER_FOR_YOU");
        }

        // ==== 5. INFO / STATS ====
        T.section("5. requestGameInfo / requestGameStats");
        try (TC c = new TC(port, 40000)) {
            T.ok("login", c.login("t_user", "pw", 40000));
            GameInfoPayload info = c.asGameInfo(c.gameInfo(-1));
            T.cond("remainingWords 4 dopo 3 gruppi trovati", info.correct == 3
                    && info.remainingWords != null && info.remainingWords.size() == 4);
            T.check("gameStats live OK", c.gameStats(-1), "OK", null, null);
        }

        // ==== 6. LEADERBOARD ====
        T.section("6. requestLeaderboard");
        try (TC c = new TC(port, 40000)) {
            T.ok("login", c.login("t_user", "pw", 40000));
            Response lb = c.leaderboard(null, 2);
            T.check("leaderboard top2 OK", lb, "OK", null,
                    x -> { LeaderboardPayload l = c.asLb(x);
                           return l.leaderboard != null && l.leaderboard.size() <= 2; });
            T.err("leaderboard playerName inesistente", c.leaderboard("nope", null), "ERR_PLAYER_NOT_FOUND");
        }

        // ==== 7. PLAYER STATS ====
        T.section("7. requestPlayerStats");
        try (TC c = new TC(port, 40000)) {
            T.ok("login", c.login("t_user", "pw", 40000));
            Response ps = c.playerStats();
            T.check("playerStats OK", ps, "OK", null,
                    x -> { PlayerStatsPayload p = c.asPlayer(x);
                           return p.puzzlesCompleted >= 0 && p.mistakeHistogram != null
                                   && p.mistakeHistogram.length == 6; });
        }

        // ==== 8. UPDATE CREDENTIALS ====
        T.section("8. updateCredentials");
        try (TC c = new TC(port, 40000)) {
            T.ok("login t_ucred", c.login("t_user", "pw", 40000));
            T.ok("rinomina username", c.updateCredentials("t_user", "pw", "t_user2", null));
            // la rinomina NON sloga: la sessione resta viva
            T.ok("sessione viva dopo rinomina", c.gameInfo(-1));
            T.ok("logout", c.logout());
        }
        try (TC c = new TC(port, 40000)) {
            T.ok("login col nuovo nome", c.login("t_user2", "pw", 40000));
        }
        try (TC c2 = new TC(port, 40000)) {
            T.ok("login per cambio psw", c2.login("t_user2", "pw", 40000));
            T.ok("cambio psw", c2.updateCredentials("t_user2", "pw", null, "pw2"));
            T.ok("logout", c2.logout());
        }
        try (TC c3 = new TC(port, 40000)) {
            T.ok("login nuova psw", c3.login("t_user2", "pw2", 40000));
            T.ok("logout", c3.logout());
        }
        try (TC c4 = new TC(port, 40000)) {
            T.err("login vecchia psw ora errata", c4.login("t_user2", "pw", 40000), "ERR_WRONG_PASSWORD");
        }

        // ==== 9. INPUT HOSTILI ====
        T.section("9. input malformati / operazioni sconosciute");
        try (TC c = new TC(port, 40000)) {
            T.check("operation vuota -> UNKNOWN", c.call(TC.req("")), "ERROR", "UNKNOWN_OPERATION", null);
            T.check("operation mancante -> BAD_REQUEST", c.call(new protocol.Request()), "ERROR", "BAD_REQUEST", null);
            T.check("operation sconosciuta", c.call(TC.req("doEvilThings")), "ERROR", "UNKNOWN_OPERATION", null);
        }

        fails = T.summary();
        return fails;
    }

    private static protocol.Request noPswReq(String u) {
        protocol.Request r = new protocol.Request(); r.operation = "register"; r.username = u; 
        return r;
    }
    private static protocol.Request noUdpLoginReq(String u, String p) {
        protocol.Request r = new protocol.Request(); r.operation = "login"; r.username = u; r.psw = p; return r;
    }

    private static void loadWords(java.io.File gamesFile) throws Exception {
        Type type = new TypeToken<List<Game>>() {}.getType();
        GAMES = GSON.fromJson(new FileReader(gamesFile), type);
        for (Group g : GAMES.get(0).groups) {
            WORDS_GROUPS.add(new ArrayList<>(g.words));
        }
        G0 = WORDS_GROUPS.get(0);
        G1 = WORDS_GROUPS.get(1);
        G2 = WORDS_GROUPS.get(2);
        G3 = WORDS_GROUPS.get(3);
    }

    /**
     * Gruppi (tema+parole) del game al dato indice nel file jugo games.json.
     * Il server ruota rispetto all'ordine del file: round N -> games[N].groups.
     */
    public static List<List<String>> groupsOf(int gameIndex) {
        if (GAMES == null) {
            try { loadWords(new java.io.File("data/games.json")); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        List<List<String>> out = new ArrayList<>();
        for (Group g : GAMES.get(gameIndex).groups) out.add(new ArrayList<>(g.words));
        return out;
    }
}