package protocol.payload;

// Risposta a requestPlayerStats: statistiche personali in stile NYT.
public class PlayerStatsPayload {
    public String username; // account a cui si riferiscono le statistiche
    public int puzzlesCompleted;
    public int winRate; // percentuale (0-100)
    public int lossRate; // percentuale (0-100)
    public int currentStreak;
    public int maxStreak;
    public int perfectPuzzles;
    public int[] mistakeHistogram; // [0..3] vinte con 0..3 err, [4] perse, [5] non finite
}