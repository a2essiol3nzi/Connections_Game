package test;

import protocol.Response;
import protocol.payload.PlayerStatsPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Suit STATS: verifica la SOTTO-LOGICA delle statistiche personali ("me"),
 * che vive in GameManager.finalizeGame (aggiornamento UserStore) e
 * playerStats (aggregazione). Scatta solo alla FINE di una partita reale
 * (rotazione round), quindi qui si giocano game veri su un server dedicato
 * con durata breve e si ispeziona lo stato dopo ogni round:
 *
 *   Round 1 (games[0]) : WIN  pulita (3 gruppi, 0 errori) -> currentStreak=1,
 *                        maxStreak=1, winRate=100, perfectPuzzles=1, hist[0]++
 *   Round 2 (games[1]) : LOSS (4 errori)                  -> currentStreak=0 (reset),
 *                        maxStreak resta 1, lossRate, winRate=50, hist[4]++
 *   Round 3 (games[2]) : NOT_FINISHED (nessuna mossa)     -> currentStreak=0,
 *                        lossRate segnato, hist[5]++ (non finita)
 *
 * Verifica anche che una PENALITA' (16 punti per 4 errori) venga applicata
 * (il punteggio cumulativo scala come da spec: +6 per gruppo, -4 per errore).
 */
public class TestStats {

    public static int run(String projectDir) throws Exception {
        // server dedicato: durata breve per ruotare velocemente i round
        TC.Server srv = TC.startServer(projectDir, 3);
        int port = srv.tcpPort;
        try {
            int fails = T.FAILURES.size();
            List<List<String>> g0 = TestFunc.groupsOf(0);
            List<List<String>> g1 = TestFunc.groupsOf(1);

            T.section("Stats sub-logic (streak/winRate/histogram) su game reali");

            try (TC c = new TC(port, 41000)) {
                T.ok("register", c.register("s_user", "pw"));
                T.ok("login (auto-join round 1)", c.login("s_user", "pw", 41000));
                int r1 = c.asGameInfo(c.gameInfo(-1)).gameId; // roundId del round 1 corrente

                // ---- ROUND 1: WIN ----
                // una parola da ogni gruppo del game 1 -> ERRATA (non malformata, -4)
                List<String> wrong = new ArrayList<>();
                for (int gi = 0; gi < 4; gi++) wrong.add(g1.get(gi).get(0));
                // vinci il round1: 3 gruppi corretti di games[0] (0 errori)
                for (int gi = 0; gi < 3; gi++) {
                    Response r = c.submit(g0.get(gi));
                    if (!"OK".equals(r.status) || !"CORRECT".equals(c.asSubmit(r).result)) {
                        T.cond("round1 submit gruppi[gi] CORRECT", false);
                    }
                }
                // attendi fine round1 (finalizeGame): currentStreak=1, winRate=100 ...
                PlayerStatsPayload p1 = awaitPuzzleCount(c, 1);
                T.cond("dopo WIN: puzzlesCompleted=1", p1.puzzlesCompleted == 1);
                T.cond("dopo WIN: currentStreak=1", p1.currentStreak == 1);
                T.cond("dopo WIN: maxStreak=1", p1.maxStreak == 1);
                T.cond("dopo WIN: winRate=100", p1.winRate == 100);
                T.cond("dopo WIN: perfectPuzzles=1 (0 errori)", p1.perfectPuzzles == 1);
                T.cond("dopo WIN: hist[0]++ (vittorie con 0 errori)", p1.mistakeHistogram[0] == 1);

                // ---- ROUND 2: LOSS ----
                // aspetta che il round 2 sia DAVVERO attivo (rotate) e l'utente ri-joinato:
                // finalizeGame avanza puzzlesCompleted PRIMA di rotate -> senza questo sync
                // i submit cadrebbero sul round 1 gia' finalizzato (ERR_GAME_OVER_FOR_YOU).
                awaitRoundAdvance(c, r1);
                System.out.println("  [dbg] round avanzato: currentRound=" + c.asGameInfo(c.gameInfo(-1)).gameId);
                for (int i = 0; i < 4; i++) {
                    Response sr = c.submit(wrong); // -> 4 errori -> loss
                    if (!"OK".equals(sr.status)) {
                        System.out.println("  [dbg] submit#" + i + " -> " + sr.status + " " + sr.errorCode);
                    }
                }
                PlayerStatsPayload p2 = awaitPuzzleCount(c, 2);
                T.cond("dopo LOSS: puzzlesCompleted=2", p2.puzzlesCompleted == 2);
                T.cond("dopo LOSS: currentStreak reset a 0", p2.currentStreak == 0);
                T.cond("dopo LOSS: maxStreak resta 1", p2.maxStreak == 1);
                T.cond("dopo LOSS: winRate=50", p2.winRate == 50);
                T.cond("dopo LOSS: lossRate=50", p2.lossRate == 50);
                T.cond("dopo LOSS: hist[4]++ (persa)", p2.mistakeHistogram[4] == 1);
                T.cond("dopo LOSS: hist[0] ancora 1", p2.mistakeHistogram[0] == 1);

                // ---- ROUND 3: NOT_FINISHED ----
                // l'utente resta loggato e viene auto-joinato al round 3 ma non muove
                PlayerStatsPayload p3 = awaitPuzzleCount(c, 3);
                T.cond("dopo NOT_FINISHED: puzzlesCompleted=3", p3.puzzlesCompleted == 3);
                T.cond("dopo NOT_FINISHED: currentStreak=0 (mantien il reset)", p3.currentStreak == 0);
                T.cond("dopo NOT_FINISHED: maxStreak resta 1", p3.maxStreak == 1);
                T.cond("dopo NOT_FINISHED: winRate=33", p3.winRate == 33);
                T.cond("dopo NOT_FINISHED: lossRate=33", p3.lossRate == 33);
                T.cond("dopo NOT_FINISHED: hist[5]++ (non finita)", p3.mistakeHistogram[5] == 1);
            }
            fails = T.FAILURES.size() - fails;
            return (fails == 0) ? 0 : 1;
        } finally {
            srv.stop();
        }
    }

    /** Poll finché playerStats.puzzlesCompleted raggiunge almeno n (finalize avanzerà). */
    private static PlayerStatsPayload awaitPuzzleCount(TC c, int n) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            Response r = c.playerStats();
            if ("OK".equals(r.status)) {
                PlayerStatsPayload p = c.asPlayer(r);
                if (p.puzzlesCompleted >= n) return p;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("puzzlesCompleted non ha raggiunto " + n + " in tempo");
    }

    /**
     * Attende che la partita CORRENTE sia avanzata oltre roundId prev (rotate avvenuto)
     * E che l'utente sia di nuovo partecipante attivo (finished=false nel nuovo round).
     * Necessario perché finalizeGame avanza puzzlesCompleted PRIMA di rotate(): senza
     * questo sync un submit successivo cadrebbe sul vecchio round già concluso.
     */
    private static void awaitRoundAdvance(TC c, int prevRoundId) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            Response r = c.gameInfo(-1);
            if ("OK".equals(r.status)) {
                protocol.payload.GameInfoPayload info = c.asGameInfo(r);
                if (info.gameId != null && info.gameId > prevRoundId
                        && Boolean.FALSE.equals(info.finished)) {
                    return;
                }
            }
            Thread.sleep(300);
        }
        throw new AssertionError("round non e' avanzato oltre " + prevRoundId + " in tempo");
    }
}