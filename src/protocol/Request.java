package protocol;

import java.util.List;

/**
 * Envelope di RICHIESTA dal client. Tutti i campi sono opzionali nel JSON:
 * GSON lascia `null` ciò che non è presente. La chiave `"operation"` è obbligatoria.
 * Condiviso tra client (seriale) e server (deseriale).
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

    // login: porta UDP su cui il client ascolta notifiche (OBBLIGATORIA per login)
    public Integer udpPort;

    // submitProposal: le 4 parole proposte
    public List<String> words;

    // requestGameInfo / requestGameStats (-1 = partita corrente)
    public Integer roundId;

    // requestLeaderboard
    public String playerName;
    public Integer topPlayers;
}