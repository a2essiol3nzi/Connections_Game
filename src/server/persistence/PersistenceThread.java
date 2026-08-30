package server.persistence;

import server.core.UserStore;

// Thread di persistenza periodica: salva gli utenti su JSON a intervalli.
public class PersistenceThread implements Runnable {

    private final UserStore users;
    private final int intervalSec;

    public PersistenceThread(UserStore users, int intervalSec) {
        this.users = users;
        this.intervalSec = intervalSec;
    }

    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(intervalSec * 1000L);
                users.persist();
                // Storico partite cambia solo a fine partita: lo salva già il
                // scheduler (post-finalize) + lo shutdown hook; qui non serve il
                // persist periodico.
                System.out.println("[Persist] users saved");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[Persist] save failed: " + e.getMessage());
            }
        }
    }
}
