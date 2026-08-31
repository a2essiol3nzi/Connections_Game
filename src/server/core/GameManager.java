package server.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonIOException;

import protocol.payload.GameInfoPayload;
import protocol.payload.GameStatsPayload;
import protocol.payload.GroupPayload;
import protocol.payload.LeaderboardPayload;
import protocol.payload.PlayerStatsPayload;
import server.loader.GameLoader;
import server.loader.GameLoader.CyclicGameIterator;
import server.model.GameData;
import server.network.UdpRegistry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Random;

/**
 * GameManager: cuore della LOGICA DI GIOCO lato server.
 *
 * GameManager tiene l'UNICA partita attiva e, a ogni rotazione,
 * ne chiede UNA ALLA VOLTA all'iteratore ciclico del loader.
 *
 * Responsabilità:
 *   - mantenere l'ActiveGame corrente e ruotarlo allo scadere, re-iscrivendo
 *     automaticamente gli utenti online;
 *   - esporre le operazioni richieste dal ClientHandler
 *     (join, submitProposal, info/stats partita, classifica, stats utente);
 *   - registrare gli esiti a fine partita in uno storico PERSISTITO e aggiornare
 *     le statistiche utente (UserStore).
 *
 * Concorrenza: lo swap di `current` è in metodi synchronized. La valutazione è
 * delegata ad ActiveGame (synchronized); `finalized` è AtomicBoolean per visibilità
 * cross-thread e set atomico. Lo storico è in ConcurrentHashMap; onlineUsers è un
 * ConcurrentHashMap-backed set.
 */
public class GameManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final GameLoader loader; // sorgente partite PIGRO
    private final int durationSec; // durata partita (da config)
    private final Random rnd = new Random();
    private final CyclicGameIterator games; // iteratore ciclico sul loader pigro

    // Partita globale attiva (null solo se il loader è vuoto).
    // volatile + costruita PRIMA dell'assegnazione -> visibilità corretta senza lock in lettura.
    private volatile ActiveGame current;
    // Storico partite CONCLUSE: roundId -> (groups con tema + userId -> esito/score).
    // Senza limite (richiesta di progetto): cresce un round (~KB/history a 600s/partita,
    // ~160 round/giorno => ~KB-GB giornaliere) e l'intera mappa e' TENUTA IN RAM per
    // servire richieste storiche arbitrarie. Il costo in RAM è proporzionale a giorni di
    // runtime: poche centinaia di KB al giorno, irrilevante per una JVM (heap default già
    // ≥256MB); il vero costo crescente è il file e il BOOT (loadHistory deserializza tutto).
    // TRADEOFF accettato per scope didattico: persist su JSON senza DB. In produzione con
    // storico illimitato si userebbe un DB (WAL/append) che evita RAM+rewrite-integrali.
    private final Map<Integer, GameHistory> history = new ConcurrentHashMap<>();
    // Utenti attualmente loggati (per auto-join).
    private final Set<Integer> onlineUsers = ConcurrentHashMap.newKeySet();
    // Id univoco e monotono di ogni partita (non si ripete al ciclo del loader).
    private final AtomicInteger nextRoundId = new AtomicInteger(1);

    private final String historyFile;

    private static final class GameHistory {
        public int roundId;
        public int sourceGameId;
        public GameData.Group[] groups = new GameData.Group[4]; // 4 gruppi x 4 parole + tema
        public Map<Integer, HistoryEntry> entries = new HashMap<>(); // chiave = userId immutabile
    }

    public static final class HistoryEntry {
        public int correct, errors, score;
        public String outcome; // WON | LOST | NOT_FINISHED
    }

    public GameManager(GameLoader loader, int durationSec, String historyFile) {
        this.loader = loader;
        this.durationSec = durationSec;
        this.historyFile = historyFile;
        this.games = loader.cyclicIterator();
        loadHistory();
        this.current = makeNext(System.currentTimeMillis());
    }

    /* Costruisce la prossima partita: delega al loader pigro.
     * GameLoader.next() ingoia da solo i giochi malformati (JsonSyntaxException
     * gestita internamente con skip + wrap ciclico) e lancia SOLO eccezioni da
     * I/O, normalizzate in UncheckedIOException (reader) o JsonIOException (Gson).
     * Su I/O reale torniamo null -> lo scheduler ritenta. Nessun loop/retry qui:
     * il loader gia' avanza e fa wrap, quindi next() torna sempre o lancia I/O. 
     */
    private ActiveGame makeNext(long nowMs) {
        if (loader.total() == 0)
            return null;
        try {
            GameData src = games.next();
            return new ActiveGame(src, nowMs, durationSec * 1000L, rnd, nextRoundId.getAndIncrement());
        } catch (UncheckedIOException | JsonIOException e) {
            System.err.println("[GameManager] loader I/O error (fatale): " + e.getMessage());
            return null;
        }
    }

    public ActiveGame current() { return current; }

    // Lo scheduler chiama questo allo scadere della partita corrente.
    public synchronized void rotate(long nowMs) {
        current = makeNext(nowMs);
        if (current == null) 
            return;
        // Auto-join di tutti gli utenti online.
        // snapshot per iter stabile (attualmente non necessaria).
        for (int u : new ArrayList<>(onlineUsers))
            current.join(u);
    }

    // True se l'utente è attualmente nel registry online.
    public boolean isOnline(int userId) {
        return onlineUsers.contains(userId);
    }

    /**
     * Registra un login: entra nel registry online e si unisce alla partita corrente.
     * ATOMICO: se l'utente è già online, ritorna false e non modifica lo stato.
     * NOTA (sincronizzazione): serializza con rotate() per evitare race tra current()
     * e join() (fondamentale per evitare doppie join nelle partite).
     * Ritorna true se il login è riuscito, false se utente già online.
     */
    public synchronized boolean registerLogin(int userId) {
        if (!onlineUsers.add(userId))
            return false;  // utente già online
        ActiveGame g = current();
        if (g != null)
            g.join(userId);
        return true;
    }

    // Registra un logout/disconnessione: rimuove l'utente dal registry online.
    public synchronized void registerLogout(int userId) {
        onlineUsers.remove(userId);
    }

    /**
     * Completa logout ATOMICO: rimuove da online + pulisce endpoint UDP.
     * Necessario per evitare che endpoint rimanga registrato con exception
     * nella sequenza logout. 
     * Delegato al ClientHandler tramite Context.
     * (Nota: Context avrà reference a UdpRegistry per passarlo qui).
     */
    public synchronized void logoutUser(int userId, UdpRegistry udpRegistry) {
        onlineUsers.remove(userId);
        if (udpRegistry != null) {
            udpRegistry.unregister(userId);
        }
    }

    // Tentativo di partecipazione alla partita corrente. Ritorna un
    // ActiveGame.JoinResult (enum).
    public ActiveGame.JoinResult join(int userId) {
        ActiveGame g = current();
        if (g == null)
            return ActiveGame.JoinResult.ERR_NO_ACTIVE_GAME;
        return g.join(userId);
    }

    /**
     * Valuta una proposta. Ritorna un ActiveGame.SubmitResult (enum).
     * NON synchronized: la TOCTOU tra current() e submit() è gestita a valle da
     * ActiveGame.finalized (AtomicBoolean) + finalizeGame idempotente: un submit
     * sull'ex-partita al rotate torna ERR_FINISHED (semantica sana). Il vero
     * parallelismo è garantito dal lock granulare su PlayerState in ActiveGame.submit.
     */
    public ActiveGame.SubmitResult submitProposal(int userId, List<String> words) {
        ActiveGame g = current();
        if (g == null)
            return ActiveGame.SubmitResult.ERR_NO_ACTIVE_GAME;
        return g.submit(userId, words);
    }

    /**
     * Info partita:
     *   roundId == -1 (default) O roundId corrente -> partita CORRENTE (live): 
     *                    id, tempo rimanente, stato del giocatore richiesto, parole rimaste.
     *   roundId != -1 e != corrente -> partita CONCLUSA (storico): assignment
     *                    corretto (16->4, tema incluso) + correct/errors/score.
     */
    public GameInfoPayload gameInfo(int userId, int roundId, UserStore store) {
        GameInfoPayload p = new GameInfoPayload();
        ActiveGame g = currentFor(roundId);
        if (g != null) {
            p.gameId = g.roundId;
            p.sourceGameId = g.gameId;
            p.remainingSec = (int) Math.max(0, (g.endTimeMs - System.currentTimeMillis()) / 1000);
            PlayerState ps = g.getState(userId);
            if (ps != null) {
                synchronized (ps) {
                    p.correct = ps.correctCount;
                    p.errors = ps.errorCount;
                    p.score = ps.score();
                    p.finished = ps.finished;
                    p.remainingWords = new ArrayList<>(g.shuffledWords);
                    p.remainingWords.removeAll(g.wordsOfFoundGroups(ps));
                }
            }
            return p;
        }
        if (roundId == -1)
            return null; // -> ERR_NO_ACTIVE_GAME (handler)
        // storico
        GameHistory h = history.get(roundId);
        if (h == null) 
            return null; // -> ERR_GAME_NOT_FOUND (handler)
        p.gameId = h.roundId;
        p.sourceGameId = h.sourceGameId;
        p.finished = true;
        p.assignment = new ArrayList<>();
        for (GameData.Group grp : h.groups) {
            GroupPayload gp = new GroupPayload();
            gp.theme = grp.theme;
            gp.words = grp.words;
            p.assignment.add(gp);
        }
        UserStore.User u = store.getById(userId); // risoluzione per id immutabile (chiave dello storico)
        if (u == null) 
            return null; // -> ERR_GAME_NOT_FOUND (handler)
        HistoryEntry he = h.entries.get(u.id);
        if (he != null) {
            p.correct = he.correct;
            p.errors = he.errors;
            p.score = he.score;
            p.outcome = he.outcome;
        }
        return p;
    }

    /**
     * Statistiche aggregate della partita.
     *   roundId == -1 (default) O roundId corrente -> conteggi LIVE
     *                    (in corso / finiti / vinti + remainingSec).
     *   roundId != -1 e != corrente -> da `history` (partecipanti, vinti, media).
     */
    public GameStatsPayload gameStats(int roundId) {
        GameStatsPayload p = new GameStatsPayload();
        ActiveGame g = currentFor(roundId);
        if (g != null) {
            int inProgress = 0, finished = 0, won = 0;
            Set<Integer> parts = g.participants();
            // Partecipanti "presenti" = chi è ANCORA online.
            Set<Integer> present = new HashSet<>(parts);
            present.retainAll(onlineUsers);
            for (int u : present) {
                PlayerState ps = g.getState(u);
                if (ps == null)
                    continue;
                synchronized (ps) {
                    if (ps.finished) {
                        finished++;
                        if (ps.correctCount >= 3)
                            won++;
                    }
                    else inProgress++;
                }
            }
            p.participantsTotal = present.size();
            p.inProgress = inProgress;
            p.finished = finished;
            p.won = won;
            p.remainingSec = (int) Math.max(0, (g.endTimeMs - System.currentTimeMillis()) / 1000);
            return p;
        }
        if (roundId == -1)
            return null; // -> ERR_GAME_NOT_FOUND (handler)
        // storico
        GameHistory h = history.get(roundId);
        if (h == null) 
            return null; // -> ERR_GAME_NOT_FOUND (handler)
        int sum = 0, cnt = 0, w = 0;
        for (HistoryEntry e : h.entries.values()) {
            sum += e.score; cnt++;
            if ("WON".equals(e.outcome))
                w++;
        }
        p.participantsTotal = cnt;
        p.finished = cnt;
        p.won = w;
        p.avgScore = cnt == 0 ? 0 : sum / cnt;
        return p;
    }

    // Risolve la partita corrente per una richiesta: `current` se roundId è il default
    // (-1) o coincide col roundId della partita attiva, null in caso contrario (-> storico).
    private ActiveGame currentFor(int roundId) {
        ActiveGame g = current();
        if (g == null) 
            return null;
        return (roundId == -1 || roundId == g.roundId) ? g : null;
    }

    // Classifica: utenti per punteggio cumulativo; opz. rango di uno. Rank sulla lista COMPLETA.
    // NOTA: Snapshot dei (id, score) è una "fotografia sfocata" - colti con lock granulare su
    // user singoli, non globale. Tra la lettura di Alice e Bob, finalizeGame() potrebbe
    // aggiornare stats. Questo è ACCETTABILE: leaderboard è read-only. 
    // Evita bottleneck di serializzazione con finalizeGame().
    public LeaderboardPayload leaderboard(String playerName, Integer topK, UserStore store) {
        LeaderboardPayload p = new LeaderboardPayload();
        List<UserStore.User> all = new ArrayList<>(store.allUsers());
        int targetId = -1;
        if (playerName != null) {
            UserStore.User t = store.getByName(playerName);
            if (t != null) 
                targetId = t.id;
        }
        List<int[]> rows = new ArrayList<>();
        for (UserStore.User u : all) {
            synchronized (u) {
                rows.add(new int[]{ u.id, u.cumulativeScore });
            }
        }
        // sort e calcolo rank DOPO aver colto snapshot coerente
        rows.sort((a, b) -> Integer.compare(b[1], a[1]));
        int rank = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i)[0] == targetId) {
                rank = i + 1;
                break;
            }
        }
        // costruisci risposta
        p.leaderboard = new ArrayList<>();
        int lim = (topK != null) ? Math.min(topK, rows.size()) : rows.size();
        for (int i = 0; i < lim; i++) {
            int[] r = rows.get(i);
            UserStore.User u = store.getById(r[0]);
            LeaderboardPayload.Row row = new LeaderboardPayload.Row();
            synchronized (u) {
                row.username = (u == null) ? "" : u.username;
            }
            row.cumulativeScore = r[1];
            p.leaderboard.add(row);
        }
        if (playerName != null) {
            if (targetId == -1) return null; // -> ERR_PLAYER_NOT_FOUND (handler)
            p.playerRank = rank;
        }
        return p;
    }

    // Statistiche personali NYT-style. Identificazione per userId immutabile
    // (importante: una rinomina durante la partita non ne invalida l'accesso).
    public PlayerStatsPayload playerStats(int userId, UserStore store) {
        UserStore.User u = store.getById(userId);
        if (u == null) 
            return null;
        PlayerStatsPayload p = new PlayerStatsPayload();
        synchronized (u) {
            // snapshot atomico di tutti i campi sotto lock per evitare dirty read
            p.username = u.username; // nome corrente (sotto lock, dopo rinomina)
            int played = u.puzzlesPlayed;
            int won = u.puzzlesWon;
            int lost = u.puzzlesLost;
            int currentStreak = u.currentStreak;
            int maxStreak = u.maxStreak;
            int perfectPuzzles = u.perfectPuzzles;
            int[] mistakeHist = new int[u.mistakeHist.length];
            System.arraycopy(u.mistakeHist, 0, mistakeHist, 0, u.mistakeHist.length);
            // calcoli sulla snapshot (sotto lock, coerenti)
            p.puzzlesCompleted = played;
            p.winRate = played == 0 ? 0 : (100 * won / played);
            p.lossRate = played == 0 ? 0 : (100 * lost / played);
            p.currentStreak = currentStreak;
            p.maxStreak = maxStreak;
            p.perfectPuzzles = perfectPuzzles;
            p.mistakeHistogram = mistakeHist;
        }
        return p;
    }

    // A fine partita: chiude la partita, registra esiti in storico e aggiorna UserStore.
    // IDEMPOTENTE: se già finalizzata, ritorna senza ricontare le stat.
    public synchronized void finalizeGame(UserStore store) {
        ActiveGame g = current;
        if (g == null) 
            return;
        // Sigillo idempotente: solo la prima finalizzazione procede, le successive no-op.
        if (!g.finalized.compareAndSet(false, true))
            return;
        GameHistory h = new GameHistory();
        h.roundId = g.roundId;
        h.sourceGameId = g.gameId;
        h.groups = g.groupInfo();
        for (int u : g.participants()) {
            HistoryEntry he;
            ActiveGame.Outcome oc;
            // Nessun lock aggiuntivo, finalized=true previene concurrent modification
            PlayerState ps = g.getState(u);
            if (ps == null) continue;
            oc = g.outcomeOf(ps); // già synchronized(ps)
            he = new HistoryEntry();
            // Lock esplicito su ps, protegge da submit concorrenti.
            synchronized (ps) {
                he.correct = ps.correctCount; 
                he.errors = ps.errorCount;
                he.score = ps.score();
            }
            he.outcome = oc.name();
            // Risoluzione per id IMMUTABILE: sopravvive alla rinomina delle credenziali
            // avvenuta durante la partita (fix: lo stato di gioco è chiave per userId).
            UserStore.User user = store.getById(u);
            if (user == null) // utente non persistito: niente storico, niente stat
                continue;
            h.entries.put(user.id, he); // chiave = id immutabile, non username
            synchronized (user) {
                user.puzzlesPlayed++;
                user.cumulativeScore += he.score;
                if (oc == ActiveGame.Outcome.WON) {
                    user.puzzlesWon++;
                    user.currentStreak++;
                    user.maxStreak = Math.max(user.maxStreak, user.currentStreak);
                    if (he.errors == 0) user.perfectPuzzles++;
                    user.mistakeHist[he.errors]++;
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
        history.put(h.roundId, h);
    }

    // Lo storico cresce INDEFINITAMENTE: nessun trim.
    // Nota: persistHistory() riscrive l'INTERO file JSON a ogni partita; col
    // tempo il file cresce senza limite (scelta consapevole per questo scope
    // didattico: in produzione si userebbe un DB append-only/WAL).

    /**
     * PERSISTENZA STORICO
     * Scelta consapevole per questo progetto didattico: storico intero riscritto su file
     * JSON atomico. In produzione aziendale potremmo usare un DB (append-only/WAL) che evita
     * di riscrivere tutto a ogni salvataggio; QUI il file si allarga di un round (~KB) a 
     * ogni partita: riscrittura integrale adeguata e accettabile per questo scopo (niente early-optimization).
     * Gson serializza STREAMED verso il writer (toJson(map, w)): niente String/JsonElement
     * intermedia dell'intero storico in RAM = memoria extra O(1). history.put avviene SOLO in
     * finalizeGame (synchronized), chiamato dal solo scheduler => unico scrittore, nessun lock
     * aggiuntivo necessario per lo snapshot. I/O su ioLock dedicato -> non blocca il gameplay. 
     */ 
    private final Object ioLock = new Object();

    public void persistHistory() throws IOException {
        synchronized (ioLock) {
            Path p = Paths.get(historyFile);
            if (p.getParent() != null) 
                Files.createDirectories(p.getParent());
            Path tmp = Paths.get(historyFile + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(history, w);
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /**
     * Deserializza l'INTERO storico in RAM all'avvio (single-thread). Con storico
     * senza limite il tempo/memoria di boot crescono col file accumulato. TRADEOFF
     * accettato: servire una richiesta storico arbitraria richiede la mappa in RAM.
     * (Alternativa lazy: indicizzare per-round su disco e load su richiesta - non
     * fatta, early-optimization a questa scala)
     */
    private void loadHistory() {
        Path p = Paths.get(historyFile);
        if (!Files.exists(p)) 
            return;
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<ConcurrentHashMap<Integer, GameHistory>>(){}.getType();
            ConcurrentHashMap<Integer, GameHistory> loaded = GSON.fromJson(r, type);
            if (loaded == null) 
                return;
            history.putAll(loaded);
            int max = 0;
            for (int k : loaded.keySet()) 
                max = Math.max(max, k);
            nextRoundId.set(max + 1); // si riparte da dove lasciato
        } catch (Exception e) {
            System.err.println("[GameManager] load history fallito (" + e.getMessage() + "), parto vuoto");
        }
    }
}
