package protocol.payload;

/**
 * Risposta a requestGameStats.
 *  - live: participantsTotal, inProgress, finished, won, remainingSec
 *  - storico: participantsTotal, finished, won, avgScore
 * (campi non pertinenti restano null e vengono omessi dal JSON).
 */
public class GameStatsPayload {
    public Integer participantsTotal;
    public Integer inProgress; // solo live
    public Integer finished;
    public Integer won;
    public Integer remainingSec; // solo live
    public Integer avgScore; // solo storico
}