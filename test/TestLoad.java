import protocol.Response;
import protocol.payload.GameInfoPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Suit di CARICO/CONCORRENZA: cerca colli di bottiglia e race.
 *  L1 login concorrenti (throughput)
 *  L2 submit paralleli (lock granulare su PlayerState -> no serializzazione globale)
 *  L3 doppio login -> ERR_ALREADY_LOGGED_IN + relogin dopo EOF
 *  L4 leaderboard sotto letture concorrenti
 *  L5 disconnect brusco (EOF) senza crash
 */
public class TestLoad {

    public static int run(int port) throws Exception {
        int fails = 0;
        final int TS = (int) (System.nanoTime() % 100000);
        // client concorrenti limitato al pool del test (TC.startServer: pool.size=16):
        // con la politica di rifiuto "AbortPolicy" chi supera il tetto viene disconnesso.
        final int nUsers = 16;

        // ==== L1. login concorrenti ====
        T.section("L1. login concorrenti (" + nUsers + " utenti)");
        AtomicInteger l1fail = new AtomicInteger();
        long t0 = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < nUsers; i++) {
            final int fi = i;
            final String uname = "u" + TS + "_" + fi;
            Thread th = new Thread(() -> {
                try (TC c = new TC(port, 40000 + (fi%100))) {
                    c.register(uname + "x", "pw");
                    Response r = c.login(uname + "x", "pw", 40000);
                    if (!"OK".equals(r.status)) 
                        l1fail.incrementAndGet();
                } catch (Exception e) { l1fail.incrementAndGet(); }
            });
            th.start(); threads.add(th);
        }
        for (Thread th : threads) 
            th.join();
        long total = System.nanoTime() - t0;
        T.cond("L1: 0 login falliti", l1fail.get() == 0);
        System.out.printf("  %d login concorrenti in %.0f ms (~%.0f login/s)%n",
                nUsers, total / 1e6, nUsers / (total / 1e9));

        // ==== L2. submit paralleli ====
        T.section("L2. submit paralleli (lock granulare)");
        final int nW = 12;
        AtomicInteger l2err = new AtomicInteger();
        List<Thread> st = new ArrayList<>();
        long gt0 = System.nanoTime();
        for (int i = 0; i < nW; i++) {
            final int fi = i;
            final String uname = "v" + TS + "_" + fi;
            Thread th = new Thread(() -> {
                try (TC c = new TC(port, 40100 + (int)fi)) {
                    c.register(uname + "q", "pw");
                    if (!"OK".equals(c.login(uname + "q", "pw", 40200).status)) return;
                    GameInfoPayload info = c.asGameInfo(c.gameInfo(-1));
                    List<String> rem = info.remainingWords;
                    if (rem == null || rem.size() < 4) return;
                    List<String> words = new ArrayList<>(rem.subList(0, 4));
                    Collections.sort(words);
                    for (int k = 0; k < 3; k++) c.submit(words);
                    Response r = c.submit(Arrays.asList(words.get(0), words.get(0), words.get(1), words.get(2)));
                    if (!"ERR_MALFORMED".equals(r.errorCode)) l2err.incrementAndGet();
                } catch (Exception e) { l2err.incrementAndGet(); }
            });
            th.start(); st.add(th);
        }
        for (Thread th : st) th.join();
        long gt = System.nanoTime() - gt0;
        T.cond("L2: submit paralleli senza errori", l2err.get() == 0);
        System.out.printf("  %d client x 4 submit in %.0f ms%n", nW, gt / 1e6);

        // ==== L3. doppio login + relogin dopo EOF ====
        T.section("L3. doppio login -> ERR_ALREADY_LOGGED_IN");
        String du = "x" + TS + "_dup";
        try (TC ca = new TC(port, 40300); TC cb = new TC(port, 40301)) {
            ca.register(du, "pw");
            T.ok("login 1", ca.login(du, "pw", 40300));
            T.err("login 2 (altra conn) -> ALREADY", cb.login(du, "pw", 40301), "ERR_ALREADY_LOGGED_IN");
        }
        // chiusura della prima conn (EOF) -> l'utente si sblocca
        try (TC cc = new TC(port, 40400)) {
            T.check("relogin dopo disconnect", cc.login(du, "pw", 40400), "OK", null, null);
        }

        // ==== L4. leaderboard concorrenti ====
        T.section("L4. leaderboard concorrenti (read-heavy)");
        final int nRd = 16;
        AtomicInteger l4err = new AtomicInteger();
        List<Thread> lt = new ArrayList<>();
        long lb0 = System.nanoTime();
        for (int i = 0; i < nRd; i++) {
            Thread th = new Thread(() -> {
                try (TC c = new TC(port, 40500)) {
                    String un = "r" + TS + "_" + System.nanoTime();
                    c.register(un, "pw");
                    Response lo = c.login(un, "pw", 40500);
                    if (!"OK".equals(lo.status)) { l4err.incrementAndGet(); return; }
                    for (int k = 0; k < 5; k++) {
                        Response r = c.leaderboard(null, 10);
                        if (!"OK".equals(r.status)) l4err.incrementAndGet();
                    }
                } catch (Exception e) { l4err.incrementAndGet(); }
            });
            th.start(); lt.add(th);
        }
        for (Thread th : lt) th.join();
        long lb = System.nanoTime() - lb0;
        T.cond("L4: leaderboard senza errori", l4err.get() == 0);
        System.out.printf("  %d client x 5 leaderboard in %.0f ms%n", nRd, lb / 1e6);

        // ==== L5. disconnect brusco ====
        T.section("L5. disconnect brusco (EOF) senza crash");
        final int nDisc = 15;
        List<Thread> dt = new ArrayList<>();
        for (int i = 0; i < nDisc; i++) {
            final String uname = "d" + TS + "_" + i;
            Thread th = new Thread(() -> {
                try (TC c = new TC(port, 40600)) {
                    c.register(uname, "pw");
                    c.login(uname, "pw", 40600);
                } catch (Exception ignored) {} // chiude senza logout
            });
            th.start(); dt.add(th);
        }
        for (Thread th : dt) th.join();
        try (TC c = new TC(port, 40700)) {
            T.check("server ancora attivo dopo disconnect", c.logout(), "ERROR", "ERR_NOT_LOGGED_IN", null);
        }

        fails = T.summary();
        return fails;
    }
}