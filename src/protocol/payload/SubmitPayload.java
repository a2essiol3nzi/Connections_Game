package protocol.payload;

/**
 * Risposta a submitProposal: esito della proposta (CORRECT/WRONG) più il
 * nuovo stato della partita del richiedente.
 */
public class SubmitPayload {
    public String result; // "CORRECT" | "WRONG"
    public GameInfoPayload game; // stato aggiornato del giocatore
}