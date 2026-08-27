package protocol.payload;

import java.util.List;

/**
 * Risposta a requestGameInfo. Copre sia la partita CORRENTE (live) sia quella
 * CONCLUSa (storico): i campi non pertinenti al caso restano null e vengono
 * OMESSI dal JSON (Gson di default non serializza i null).
 *  - live: gameId, sourceGameId, remainingSec, correct, errors, score,
 *          finished, remainingWords
 *  - storico: gameId, sourceGameId, finished=true, assignment[],
 *             correct, errors, score, outcome
 */
public class GameInfoPayload {
    public Integer gameId; // roundId univoco (live) o del round storico
    public Integer sourceGameId; // id sorgente nel file partite
    public Integer remainingSec; // solo live
    public Integer correct;
    public Integer errors;
    public Integer score;
    public Boolean finished;
    public String outcome; // WON | LOST | NOT_FINISHED (solo storico)
    public List<String> remainingWords; // solo live
    public List<GroupPayload> assignment; // solo storico: gruppi con tema + parole
}