package server.protocol;

import java.util.List;

/**
 * Envelope di RICHIESTA dal client (§5). Tutti i campi sono opzionali nel JSON:
 * GSON lascia `null` ciò che non è presente. La chiave `"operation"` è obbligatoria.
 *
 * Pacchetto `protocol`: contiene i tipi di scambio messaggi condivisi tra
 * client e server (Request, Response, Errors).
 */
public class Request {

    public String operation;   // register | login | logout | submitProposal | ...

    // register / login / updateCredentials
    public String username;
    public String psw;
    public String oldUsername;
    public String oldPsw;
    public String newUsername;
    public String newPsw;

    // submitProposal: le 4 parole proposte
    public List<String> words;

    // requestGameInfo / requestGameStats (-1 = partita corrente)
    public Integer gameId;

    // requestLeaderboard
    public String playerName;
    public Integer topPlayers;
}
