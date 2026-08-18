package server.core;

import server.model.GameData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Random;

/**
 * Partita ATTIVA (unica, globale). Gestisce valutazione proposte e stato per giocatore.
 * Parole inviate MESCOLANO; i "theme" restano lato server (mai al client).
 *
 * Concorrenza: mutazioni su `this` tramite metodi synchronized (vincolo NO locks).
 * # ponytail: synchronized su ActiveGame copre valutazione + swap; ristringere
 * la sezione critica solo se il throughput reale lo richiede.
 */
public class ActiveGame {

    public final int gameId;
    public final long startTimeMs;
    public final long endTimeMs;
    private final List<Group> groups;          // 4 gruppi, con parole + tema nascosto
    public final List<String> shuffledWords;   // 16 parole in ordine casuale (al client)

    private final Map<String, PlayerState> players = new ConcurrentHashMap<>();

    private static final class Group {
        final String theme;
        final Set<String> words;
        Group(String theme, Set<String> words) { this.theme = theme; this.words = words; }
    }

    public ActiveGame(GameData src, long nowMs, long durationMs, Random rnd) {
        this.gameId = src.gameId;
        this.startTimeMs = nowMs;
        this.endTimeMs = nowMs + durationMs;
        this.groups = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (GameData.Group g : src.groups) {
            groups.add(new Group(g.theme, new HashSet<>(g.words)));
            all.addAll(g.words);
        }
        Collections.shuffle(all, rnd);
        this.shuffledWords = Collections.unmodifiableList(all);
    }

    public synchronized void join(String username) {
        players.computeIfAbsent(username, PlayerState::new);
    }

    public synchronized PlayerState getState(String username) {
        return players.get(username);
    }

    public synchronized Set<String> participants() {
        return new HashSet<>(players.keySet());
    }

    /**
     * Valuta una proposta di 4 parole per un utente.
     *   "OK_FOUND"      gruppo corretto (nuovo) → +1 corretto
     *   "OK_WRONG"      parole valide ma gruppo errato → +1 errore
     *   "ERR_MALFORMED" parole non valide / già trovate / non 4 distinte → nessun impatto
     *   "ERR_FINISHED" utente ha già finito → nessun impatto
     *   "ERR_NOTJOINED" utente non partecipa → nessun impatto
     */
    public synchronized String submit(String username, List<String> words) {
        PlayerState ps = players.get(username);
        if (ps == null) return "ERR_NOTJOINED";
        if (ps.finished) return "ERR_FINISHED";

        // 1) validità formale (MALFORMED se fallisce → nessun impatto stato)
        if (words == null || words.size() != 4) return "ERR_MALFORMED";
        Set<String> proposed = new HashSet<>(words);
        if (proposed.size() != 4) return "ERR_MALFORMED";              // duplicati
        for (String w : proposed)
            if (!shuffledWords.contains(w)) return "ERR_MALFORMED";    // parola fuori gioco
        // parola già in un gruppo trovato → MALFORMED (non errore)
        for (int gi : ps.foundGroups)
            if (proposed.containsAll(groups.get(gi).words)) return "ERR_MALFORMED";

        // 2) correttezza gruppo
        for (int gi = 0; gi < groups.size(); gi++) {
            if (proposed.equals(groups.get(gi).words)) {
                if (!ps.foundGroups.contains(gi)) {
                    ps.foundGroups.add(gi);
                    ps.correctCount++;
                    if (ps.correctCount >= 3) ps.finished = true;
                    return "OK_FOUND";
                } else {
                    return "ERR_MALFORMED"; // già trovato questo gruppo
                }
            }
        }
        // 3) nessun gruppo coincide → ERRATA (conta come errore)
        ps.errorCount++;
        if (ps.errorCount >= 4) ps.finished = true;
        return "OK_WRONG";
    }

    /** Parole dei gruppi già individuati dal giocatore (calcolo "remaining"). */
    public synchronized List<String> wordsOfFoundGroups(PlayerState ps) {
        List<String> out = new ArrayList<>();
        for (int gi : ps.foundGroups) out.addAll(groups.get(gi).words);
        return out;
    }

    public synchronized boolean isExpired(long nowMs) {
        return nowMs >= endTimeMs;
    }

    public enum Outcome { WON, LOST, NOT_FINISHED }
    public synchronized Outcome outcomeOf(PlayerState ps) {
        if (ps.correctCount >= 3) return Outcome.WON;
        if (ps.errorCount >= 4) return Outcome.LOST;
        return Outcome.NOT_FINISHED;
    }
}
