package server.core;

import server.model.GameData;
import protocol.Errors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Locale;
import java.util.Random;

/**
 * Partita ATTIVA (unica, globale). Gestisce valutazione proposte e stato per giocatore.
 *
 * Concorrenza:
 *   - `finalized` (AtomicBoolean): lettura volatile, segnala conclusione partita;
 *   - `join()`: synchronized su this (è raro, eseguito solo al login);
 *   - `submit()`: lock GRANULARE su PlayerState: N client sottomettono in parallelo;
 *   - `players` (ConcurrentHashMap): thread-safe lookups senza lock esterno;
 *   - Validazioni read-only (shuffledWords, groups): thread-safe senza lock
 *     (immutabili dopo costruzione).
 */
public class ActiveGame {

    public final int gameId;        // id sorgente (si ripete al wrap del loader ciclico)
    public final int roundId;       // id UNIVOCO di questa esecuzione (monotono)
    public final long startTimeMs;
    public final long endTimeMs;
    private final List<ThemeGroup> groups;     // 4 gruppi, con parole + tema nascosto
    public final List<String> shuffledWords;   // 16 parole in ordine casuale (al client)

    // true dopo che lo scheduler ha finalizzato la partita.
    public final AtomicBoolean finalized = new AtomicBoolean(false);

    // userId -> PlayerState
    private final Map<Integer, PlayerState> players = new ConcurrentHashMap<>();

    // Struttura di RUNTIME interna: parole in Set<String> per match senza ordine in submit().
    // Distinta da GameData.Group. `wordsLower` è la variante minuscola per il
    // confronto case-insensitive (ciao == CIAO); le parole ORIGINALI restano in
    // `words`, usate per l'invio al client e per lo storico.
    private static final class ThemeGroup {
        final String theme;
        final Set<String> words;        // parole originali (come nel file)
        final Set<String> wordsLower;   // stesse parole in minuscolo (per il confronto)

        ThemeGroup(String theme, Set<String> words) {
            this.theme = theme;
            this.words = words;
            this.wordsLower = lowerOf(words);
        }
    }

    public ActiveGame(GameData src, long nowMs, long durationMs, Random rnd, int roundId) {
        this.gameId = src.gameId;
        this.roundId = roundId;
        this.startTimeMs = nowMs;
        this.endTimeMs = nowMs + durationMs;
        this.groups = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (GameData.Group g : src.groups) {
            groups.add(new ThemeGroup(g.theme, new HashSet<>(g.words)));
            all.addAll(g.words);
        }
        Collections.shuffle(all, rnd);
        this.shuffledWords = Collections.unmodifiableList(all);
        this.boardLower = lowerOf(new HashSet<>(all));
    }

    // Tutte le parole della board in minuscolo (per validare proposte case-insensitive).
    private final Set<String> boardLower;

    // Converte una raccolta di stringhe nel relativo set di minuscole.
    private static Set<String> lowerOf(Collection<String> words) {
        Set<String> out = new HashSet<>(words.size());
        for (String w : words) 
            out.add(w.toLowerCase(Locale.ROOT));
        return out;
    }

    // Esito di una partecipazione. 
    public enum JoinResult {
        OK(null, null),
        ERR_FINISHED(null, Errors.ERR_GAME_OVER_FOR_YOU),
        ERR_NO_ACTIVE_GAME(null, Errors.ERR_NO_ACTIVE_GAME);

        final String result;  // etichetta esito OK (qui sempre null: join non ha payload)
        final Errors error;    // codice errore; null sui successi

        JoinResult(String result, Errors error) { this.result = result; this.error = error; }
        
        public boolean isOk() { return error == null; }
        public String resultLabel() { return result; }
        public Errors error() { return error; }
    }

    // Esito di valutazione di una proposta.
    public enum SubmitResult {
        OK_FOUND("CORRECT", null),
        OK_WRONG("WRONG", null),
        ERR_MALFORMED(null, Errors.ERR_MALFORMED),
        ERR_FINISHED(null, Errors.ERR_GAME_OVER_FOR_YOU),
        ERR_NOTJOINED(null, Errors.ERR_NOT_JOINED),
        ERR_NO_ACTIVE_GAME(null, Errors.ERR_NO_ACTIVE_GAME);

        final String result;  // etichetta esito OK ("CORRECT"/"WRONG"), null sugli errori
        final Errors error;    // codice errore; null sui successi

        SubmitResult(String result, Errors error) { this.result = result; this.error = error; }
        
        public boolean isOk() { return error == null; }
        public String resultLabel() { return result; }
        public Errors error() { return error; }
    }

    public PlayerState getState(int userId) {
        return players.get(userId);
    }

    public Set<Integer> participants() {
        return new HashSet<>(players.keySet());
    }

    // Partecipazione alla partita.
    public synchronized JoinResult join(int userId) {
        if (finalized.get())
            return JoinResult.ERR_FINISHED;
        players.computeIfAbsent(userId, PlayerState::new);
        return JoinResult.OK;
    }

    /**
     * Valuta una proposta di 4 parole per un utente.
     *   OK_FOUND      gruppo corretto (nuovo) -> +1 corretto
     *   OK_WRONG      parole valide ma gruppo errato -> +1 errore
     *   ERR_MALFORMED parole non valide / gia trovate / non 4 distinte -> nessun impatto
     *   ERR_FINISHED utente ha gia finito (o partita conclusa) -> nessun impatto
     *   ERR_NOTJOINED utente non partecipa -> nessun impatto
     *
     * CONCORRENZA: Lock GRANULARE su PlayerState (ps), NON su ActiveGame.
     * Questo consente N client di sottomettere PARALLELAMENTE proposte diverse
     * senza serializzazione. Validazioni read-only avvengono senza lock;
     * modifiche a ps sono protette da synchronized(ps).
     * 
     * Nota: per evitare TOCTOU si fa un ri-checking di finalized.
     */
    public SubmitResult submit(int userId, List<String> words) {
        if (finalized.get()) 
            return SubmitResult.ERR_FINISHED;
        PlayerState ps = players.get(userId);
        if (ps == null) 
            return SubmitResult.ERR_NOTJOINED;
        if ((words == null) || (words.size() != 4)) 
            return SubmitResult.ERR_MALFORMED;
        Set<String> proposed = new HashSet<>(words.size());
        for (String w : words) proposed.add(w.toLowerCase(Locale.ROOT)); // case-insensitive
        if (proposed.size() != 4) 
            return SubmitResult.ERR_MALFORMED; // duplicati
        for (String w : proposed)
            if (!boardLower.contains(w)) 
                return SubmitResult.ERR_MALFORMED; // parola non proposta
        synchronized (ps) {
            // Richeck finalized
            if (finalized.get()) 
                return SubmitResult.ERR_FINISHED;
            if (ps.finished) 
                return SubmitResult.ERR_FINISHED;
            for (int gi : ps.foundGroups)
                if (!Collections.disjoint(proposed, groups.get(gi).wordsLower)) // parola già usata
                    return SubmitResult.ERR_MALFORMED;
            for (int gi = 0; gi < groups.size(); gi++) {
                if (proposed.equals(groups.get(gi).wordsLower)) { // gruppo corretto
                    if (!ps.foundGroups.contains(gi)) { // gruppo nuovo
                        ps.foundGroups.add(gi);
                        ps.correctCount++;
                        if (ps.correctCount >= 3) 
                            ps.finished = true; // vittoria!
                        return SubmitResult.OK_FOUND;
                    } else { // gruppo già trovato
                        return SubmitResult.ERR_MALFORMED;
                    }
                }
            }
            // gruppo errato
            ps.errorCount++;
            if (ps.errorCount >= 4)  // sconfitta! 
                ps.finished = true;
            return SubmitResult.OK_WRONG;
        }
    }

    // Assignment completo (tema + parole); per lo storico (riusa GameData.Group).
    // Le groups sono immutabili dopo il costruttore, quindi non serve lock.
    public GameData.Group[] groupInfo() {
        GameData.Group[] out = new GameData.Group[groups.size()]; // 4 gruppi
        for (int i = 0; i < groups.size(); i++) {
            ThemeGroup g = groups.get(i);
            GameData.Group gi = new GameData.Group();
            gi.theme = g.theme;
            gi.words = new ArrayList<>(g.words); // Set -> List (forma JSON condivisa)
            out[i] = gi;
        }
        return out;
    }

    // Parole dei gruppi già individuati dal giocatore (calcolo "remaining").
    public List<String> wordsOfFoundGroups(PlayerState ps) {
        synchronized (ps) {
            List<String> out = new ArrayList<>();
            for (int gi : ps.foundGroups) 
                out.addAll(groups.get(gi).words);
            return out;
        }
    }

    public enum Outcome { WON, LOST, NOT_FINISHED }
    
    public Outcome outcomeOf(PlayerState ps) {
        synchronized (ps) {
            if (ps.correctCount >= 3) 
                return Outcome.WON;
            if (ps.errorCount >= 4) 
                return Outcome.LOST;
            return Outcome.NOT_FINISHED;
        }
    }
}
