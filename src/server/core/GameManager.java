package server.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.loader.GameLoader;
import server.model.GameData;
import server.persistence.UserStore;
import server.protocol.Errors;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GameManager: cuore della LOGICA DI GIOCO lato server.
 *
 * Il caricatore delle partite è PIGRO: le partite NON stanno tutte in RAM.
 * GameManager tiene quindi solo l'UNICA partita attiva e, a ogni rotazione,
 * ne chiede UNA al loader tramite `gameAt(indice casuale)`.
 *
 * Responsabilità:
 *   • mantenere l'ActiveGame corrente e ruotarlo allo scadere;
 *   • esporre le operazioni richieste dal ClientHandler
 *     (join, submitProposal, info/stats partita, classifica, stats utente);
 *   • registrare gli esiti a fine partita e aggiornare le statistiche utente.
 *
 * Concorrenza: lo swap di `current` è in metodi synchronized (vincolo: NO
 * java.util.concurrent.locks.*). La valutazione è delegata ad ActiveGame
 * (a sua volta synchronized). Lo storico è in ConcurrentHashMap.
 */
public class GameManager {

    private final GameLoader loader;          // sorgente partite PIGRO
    private final int durationSec;           // durata partita (da config)
    private final Random rnd = new Random();
    private final Iterator<GameData> games;  // iteratore ciclico sul loader pigro

    // Partita globale attiva (null solo se il loader è vuoto).
    private ActiveGame current;
    // Storico partite CONCLUSE: gameId -> (username -> esito/score).
    private final Map<Integer, GameHistory> history = new ConcurrentHashMap<>();

    private static final class GameHistory {
        final Map<String, HistoryEntry> entries = new ConcurrentHashMap<>();
    }
    public static final class HistoryEntry {
        public int correct, errors, score;
        public String outcome; // WON | LOST | NOT_FINISHED
    }

    public GameManager(GameLoader loader, int durationSec) {
        this.loader = loader;
        this.durationSec = durationSec;
        this.games = loader.cyclicIterator();
        this.current = makeNext(System.currentTimeMillis());
    }

    /** Costruisce la prossima partita: ciclo sul loader pigro. */
    private ActiveGame makeNext(long nowMs) {
        if (loader.total() == 0) return null;
        try {
            GameData src = games.next();
            return new ActiveGame(src, nowMs, durationSec * 1000L, rnd);
        } catch (Exception e) {
            System.err.println("[GameManager] caricamento partita fallito: " + e.getMessage());
            return null;
        }
    }

    public synchronized ActiveGame current() { return current; }

    /** Lo scheduler chiama questo allo scadere della partita corrente. */
    public synchronized void rotate(long nowMs) { current = makeNext(nowMs); }

    // ---- OPERAZIONI ESPOSTE AL CLIENTHANDLER ----

    public Errors join(String username) {
        ActiveGame g = current();
        if (g == null) return Errors.ERR_NO_ACTIVE_GAME;
        g.join(username);
        return null; // null = OK
    }

    public String submitProposal(String username, List<String> words) {
        ActiveGame g = current();
        if (g == null) return Errors.ERR_NO_ACTIVE_GAME.name();
        return g.submit(username, words);
    }

    public JsonObject gameInfo(String username, int gameId) {
        ActiveGame g = (gameId == -1) ? current() : null; // -1 = corrente
        JsonObject o = new JsonObject();
        if (g == null) { o.addProperty("errorCode", Errors.ERR_GAME_NOT_FOUND.name()); return o; }
        o.addProperty("gameId", g.gameId);
        long remain = Math.max(0, (g.endTimeMs - System.currentTimeMillis()) / 1000);
        o.addProperty("remainingSec", remain);
        PlayerState ps = g.getState(username);
        if (ps != null) {
            o.addProperty("correct", ps.correctCount);
            o.addProperty("errors", ps.errorCount);
            o.addProperty("score", ps.score());
            o.addProperty("finished", ps.finished);
            List<String> remaining = new ArrayList<>(g.shuffledWords);
            for (String w : g.wordsOfFoundGroups(ps)) remaining.remove(w);
            JsonArray rem = new JsonArray();
            for (String w : remaining) rem.add(w);
            o.add("remainingWords", rem);
        }
        return o;
    }

    /**
     * Statistiche aggregate della partita.
     *   - partita in corso (gameId==-1): conteggi LIVE (in corso / finiti / vinti).
     *   - partita storica (gameId!=-1): da `history` (partecipanti, vinti, media).
     */
    public JsonObject gameStats(int gameId) {
        ActiveGame g = (gameId == -1) ? current() : null;
        JsonObject o = new JsonObject();
        if (g == null && gameId == -1) {
            o.addProperty("errorCode", Errors.ERR_GAME_NOT_FOUND.name());
            return o;
        }
        if (g != null) {
            int inProgress = 0, finished = 0, won = 0;
            for (String u : g.participants()) {
                PlayerState ps = g.getState(u);
                if (ps.finished) { finished++; if (ps.correctCount >= 3) won++; }
                else inProgress++;
            }
            o.addProperty("participantsTotal", g.participants().size());
            o.addProperty("inProgress", inProgress);
            o.addProperty("finished", finished);
            o.addProperty("won", won);
        } else {
            // storica: leggo lo storico
            GameHistory h = history.get(gameId);
            if (h != null) {
                int sum = 0, cnt = 0, w = 0;
                for (HistoryEntry e : h.entries.values()) {
                    sum += e.score; cnt++;
                    if ("WON".equals(e.outcome)) w++;
                }
                o.addProperty("participantsTotal", cnt);
                o.addProperty("finished", cnt);
                o.addProperty("won", w);
                o.addProperty("avgScore", cnt == 0 ? 0 : sum / cnt);
            } else {
                o.addProperty("errorCode", Errors.ERR_GAME_NOT_FOUND.name());
            }
        }
        return o;
    }

    /** Classifica: utenti (o top-K) per punteggio cumulativo; opz. rango di uno. */
    public JsonObject leaderboard(String playerName, Integer topK, UserStore store) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        List<UserStore.User> all = new ArrayList<>(store.allUsers());
        all.sort((a, b) -> Integer.compare(b.cumulativeScore, a.cumulativeScore));
        if (topK != null) all = new ArrayList<>(all.subList(0, Math.min(topK, all.size())));
        for (UserStore.User u : all) {
            JsonObject e = new JsonObject();
            e.addProperty("username", u.username);
            e.addProperty("cumulativeScore", u.cumulativeScore);
            arr.add(e);
        }
        o.add("leaderboard", arr);
        if (playerName != null) {
            int pos = 1;
            for (UserStore.User u : all) {
                if (u.username.equals(playerName)) break;
                pos++;
            }
            o.addProperty("playerRank", store.hasUser(playerName) ? pos : -1);
        }
        return o;
    }

    /** Statistiche personali NYT-style (§2.1). */
    public JsonObject playerStats(String username, UserStore store) {
        UserStore.User u = store.getByName(username);
        JsonObject o = new JsonObject();
        if (u == null) { o.addProperty("errorCode", Errors.ERR_USER_NOT_FOUND.name()); return o; }
        int played = u.puzzlesPlayed;
        o.addProperty("puzzlesCompleted", played);
        o.addProperty("winRate", played == 0 ? 0 : (100 * u.puzzlesWon / played));
        o.addProperty("lossRate", played == 0 ? 0 : (100 * u.puzzlesLost / played));
        o.addProperty("currentStreak", u.currentStreak);
        o.addProperty("maxStreak", u.maxStreak);
        o.addProperty("perfectPuzzles", u.perfectPuzzles);
        JsonArray hist = new JsonArray();
        for (int x : u.mistakeHist) hist.add(x);
        o.add("mistakeHistogram", hist);
        return o;
    }

    /** A fine partita: registra esiti e aggiorna statistiche di ogni giocatore. */
    public void finalizeGame(UserStore store) {
        ActiveGame g = current();
        if (g == null) return;
        GameHistory h = history.computeIfAbsent(g.gameId, k -> new GameHistory());
        for (String u : g.participants()) {
            PlayerState ps = g.getState(u);
            ActiveGame.Outcome oc = g.outcomeOf(ps);
            int score = ps.score();
            HistoryEntry he = new HistoryEntry();
            he.correct = ps.correctCount; he.errors = ps.errorCount;
            he.score = score; he.outcome = oc.name();
            h.entries.put(u, he);

            UserStore.User user = store.getByName(u);
            if (user == null) continue;
            synchronized (user) {
                user.puzzlesPlayed++;
                user.cumulativeScore += score;
                if (oc == ActiveGame.Outcome.WON) {
                    user.puzzlesWon++;
                    user.currentStreak++;
                    user.maxStreak = Math.max(user.maxStreak, user.currentStreak);
                    if (ps.errorCount == 0) user.perfectPuzzles++;
                    user.mistakeHist[ps.errorCount]++;
                } else if (oc == ActiveGame.Outcome.LOST) {
                    user.puzzlesLost++;
                    user.currentStreak = 0;
                    user.mistakeHist[4]++;
                } else {
                    user.notFinished++;
                    user.currentStreak = 0;
                    user.mistakeHist[5]++;
                }
            }
        }
    }
}
