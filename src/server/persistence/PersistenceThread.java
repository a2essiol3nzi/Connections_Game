package server.persistence;

/**
 * Thread di persistenza periodica: salva UserStore su JSON a intervalli
 * (§2.2: persistenza periodica consistente per riavvio).
 * Pacchetto `persistence`: tutto ciò che riguarda salvare/caricare lo stato.
 */
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
