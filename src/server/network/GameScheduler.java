package server.network;

import server.core.ActiveGame;
import server.core.GameManager;
import server.core.UserStore;
import protocol.GameEnded;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler della partita attiva. Esegue periodicamente: finalizza
 * (esiti+UserStore) -> persist event-driven (stat su disco subito) -> ruota
 * alla partita successiva -> invia la notifica UDP ai partecipanti della
 * partita appena conclusa.
 * Coordinazione via ScheduledExecutorService con scheduleWithFixedDelay.
 * Lo swap di ActiveGame avviene in GameManager.rotate (synchronized).
 * 
 * La notifica UDP (type: GAME_ENDED + gameId/roundId) è un segnale, non contiene
 * i risultati. La rotazione avviene prima dell'invio: il round notificato non è
 * più `current`, quindi requestGameInfo(roundId) / requestGameStats leggono
 * deterministicamente GameManager.history.
 */
public class GameScheduler {

    private final GameManager gameMan;
    private final UserStore users;
    private final UdpNotifier notifier;
    private final long durationMs;
    private final ScheduledExecutorService executor;

    public GameScheduler(GameManager gm, UserStore users, UdpNotifier notifier, int durationSec) {
        this.gameMan = gm;
        this.users = users;
        this.notifier = notifier;
        this.durationMs = durationSec * 1000L;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "scheduler"));
    }

    // Avvia il primo task alla deadline della partita creata durante il boot.
    public void start() {
        ActiveGame g = gameMan.current();
        if (g == null) {
            System.err.println("[Scheduler] no active game; scheduler not started");
            return;
        }
        long initialDelayMs = Math.max(0, g.endTimeMs - System.currentTimeMillis());
        executor.scheduleWithFixedDelay(
            this::endCurrentGame,
            initialDelayMs,
            durationMs,
            TimeUnit.MILLISECONDS
        );
    }

    // Finalizza il round corrente e crea quello successivo prima della notifica UDP.
    private void endCurrentGame() {
        ActiveGame g = gameMan.current();
        if (g == null)
            return;
        gameMan.finalizeGame(users);
        try {
            users.persistUsers(); // persist event-driven
            gameMan.persistHistory(); // persistenza storico partite su disco
        } catch (IOException e) {
            System.err.println("[Scheduler] persist failed: " + e.getMessage());
        }
        Set<Integer> parts = g.participants();
        gameMan.rotate(System.currentTimeMillis());
        notifier.notifyEnd(parts, new GameEnded("GAME_ENDED", g.gameId, g.roundId));
        System.out.println("[Scheduler] game " + g.gameId + " (round " + g.roundId + ") ended, " + parts.size() + " players");
    }
}
