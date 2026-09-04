package protocol.payload;

import java.util.List;

/**
 * Risposta a requestLeaderboard: tabella dei giocatori per punteggio cumulativo
 * e (opzionale) rango di un utente richiesto con playerName.
 */
public class LeaderboardPayload {
    public static class Row {
        public String username;
        public int cumulativeScore;
        public Boolean requester; // (non boolean) affinché Gson ometta il campo per tutte le altre righe
    }

    public List<Row> leaderboard;
    public Integer playerRank; // solo se richiesto via playerName
}