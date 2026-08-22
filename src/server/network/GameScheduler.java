package server.network;

import server.core.ActiveGame;
import server.core.GameManager;
import server.core.UserStore;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Set;

/**
 * Scheduler della partita attiva (unico thread). Ciclo:
 *   attende scadenza partita corrente -> finalizza (esiti+UserStore) ->
 *   persist event-driven (stat su disco subito) -> invia notifica UDP a tutti
 *   i partecipanti -> ruota alla partita successiva.
 * Coordinazione via Thread.sleep fino a endTime (no wait/notify necessario).
 * Lo swap di ActiveGame avviene in GameManager.rotate (synchronized).
 */
public class GameScheduler implements Runnable {

    private final GameManager gm;
    private final UserStore users;
    private final UdpNotifier notifier;

    public GameScheduler(GameManager gm, UserStore users, UdpNotifier notifier) {
        this.gm = gm;
        this.users = users;
        this.notifier = notifier;
    }

    @Override
    public void run() {
        while (true) {
            ActiveGame g = gm.current();
            if (g == null) { 
                sleep(1000); 
                continue; 
            }
            long waitMs = g.endTimeMs - System.currentTimeMillis();
            if (waitMs > 0) 
                sleep(waitMs);

            gm.finalizeGame(users);
            try {
                users.persist(); // persist event-driven: stat su disco subito, non entro 30s
                gm.persistHistory(); // persistenza storico partite su disco
            } catch (IOException e) {
                System.err.println("[Scheduler] persist failed: " + e.getMessage());
            }
            Set<Integer> parts = g.participants();
            JsonObject note = new JsonObject();
            note.addProperty("type", "GAME_ENDED");
            note.addProperty("gameId", g.gameId);
            note.addProperty("roundId", g.roundId);
            notifier.notifyEnd(parts, note);

            System.out.println("[Scheduler] game " + g.gameId + " (round " + g.roundId + ") ended, " + parts.size() + " players");
            gm.rotate(System.currentTimeMillis());
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
