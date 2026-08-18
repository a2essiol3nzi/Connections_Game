package server.core;

import java.util.HashSet;
import java.util.Set;

/**
 * Stato di UN giocatore dentro una singola partita attiva.
 *   • foundGroups: indici (0..3) dei gruppi già individuati correttamente;
 *   • errorCount: proposte ERRATE (0..4); 4 ⇒ sconfitta;
 *   • correctCount: gruppi trovati (0..3); 3 ⇒ vittoria (4° implicito);
 *   • finished: true se ha vinto/perso (non può più inviare proposte).
 *
 * Pacchetto `core`: logica di gioco condivisa da GameManager/ActiveGame.
 */
public class PlayerState {
    public final String username;
    public final Set<Integer> foundGroups = new HashSet<>();
    public int errorCount = 0;
    public int correctCount = 0;
    public boolean finished = false;

    public PlayerState(String username) { this.username = username; }

    /** Punteggio: +6 per gruppo corretto, -4 per errore. */
    public int score() {
        return 6 * correctCount - 4 * errorCount;
    }

    public boolean alreadyFound(int groupIndex) {
        return foundGroups.contains(groupIndex);
    }
}
