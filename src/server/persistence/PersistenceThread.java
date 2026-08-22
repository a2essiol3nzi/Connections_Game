package server.persistence;

import server.core.GameManager;
import server.core.UserStore;

// Thread di persistenza periodica: salva dati importanti su JSON a intervalli.
public class PersistenceThread implements Runnable {

    private final UserStore users;
    private final GameManager games;
    private final int intervalSec;

    public PersistenceThread(UserStore users, GameManager games, int intervalSec) {
        this.users = users;
        this.games = games;
        this.intervalSec = intervalSec;
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(intervalSec * 1000L);
                users.persist();
                games.persistHistory();
                System.out.println("[Persist] users + history saved");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[Persist] save failed: " + e.getMessage());
            }
        }
    }
}
