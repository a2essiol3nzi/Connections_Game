package server.network;

import server.core.ActiveGame;
import server.core.GameManager;
import server.core.UserStore;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Set;

/**
 * Scheduler della partita attiva. Ciclo: attende scadenza partita corrente 
 * -> finalizza (esiti+UserStore) -> persist event-driven (stat su disco 
 * subito) -> invia notifica UDP a tutti i partecipanti -> ruota alla partita 
 * successiva.
 * Coordinazione via Thread.sleep fino a endTime (no wait/notify necessario).
 * Lo swap di ActiveGame avviene in GameManager.rotate (synchronized).
 * 
 * La notifica UDP (type: GAME_ENDED + gameId/roundId) è un segnale, non contiene 
 * i risultati. I client, ricevuto il segnale, vanno a leggere l'esito via TCP con 
 * requestGameInfo(roundId) / requestGameStats, che attingono dallo storico in 
 * GameManager.history. Se la notifica arrivasse prima di finalizeGame, si aprirebbe 
 * una TOCTOU window.
 */
public class GameScheduler implements Runnable {

    private final GameManager gameMan;
    private final UserStore users;
    private final UdpNotifier notifier;

    public GameScheduler(GameManager gm, UserStore users, UdpNotifier notifier) {
        this.gameMan = gm;
        this.users = users;
        this.notifier = notifier;
    }

    @Override
    public void run() {
        while (true) {
            ActiveGame g = gameMan.current();
            if (g == null) { 
                sleep(1000); // rallenta il polling / busy-wait 
                continue; 
            }
            long waitMs = g.endTimeMs - System.currentTimeMillis();
            if (waitMs > 0) 
                sleep(waitMs); // durata partita
            gameMan.finalizeGame(users);
            try {
                users.persist(); // persist event-driven
                gameMan.persistHistory(); // persistenza storico partite su disco
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
            gameMan.rotate(System.currentTimeMillis());
        }
    }

    private static void sleep(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (true) {
            long rem = deadline - System.currentTimeMillis();
            if (rem <= 0) return;
            try { Thread.sleep(rem); return; }
            catch (InterruptedException e) { /* retry */ }
        }
    }
}
