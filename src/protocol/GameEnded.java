package protocol;

/**
 * Notifica asincrona UDP inviata dal server a fine partita.
 *
 * È SOLO un segnale (type+gameId+roundId): NON contiene i risultati. Il client,
 * ricevutala, va a leggere l'esito via TCP (requestGameInfo(roundId)/Stats).
 */
public class GameEnded {

    public String type; // sempre "GAME_ENDED"
    public int gameId;
    public int roundId;

    public GameEnded() {}

    public GameEnded(String type, int gameId, int roundId) {
        this.type = type;
        this.gameId = gameId;
        this.roundId = roundId;
    }
}