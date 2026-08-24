package server.protocol;

/**
 * Codici di errore CENTRALIZZATI.
 *
 * Ogni risposta di errore del server passa da qui: niente stringhe hardcoded
 * sparse. `name()` è il codice inviato al client (es. "ERR_USER_NOT_FOUND");
 * `message` è la spiegazione umana (in cima al JSON di errore, mai nel payload).
 *
 * Convenzione unica: un metodo che ritorna `null` indica successo ("OK");
 * un valore `Errors` (o un codice stringa stile "ERR_*") indica errore.
 */
public enum Errors {

    BAD_REQUEST("Richiesta non valida (operation mancante o JSON illeggibile)"),
    ERR_INVALID("Richiesta non valida (campi mancanti/vuoti)"),
    ERR_USER_NOT_FOUND("Utente inesistente"),
    ERR_USERNAME_TAKEN("Username già registrato"),
    ERR_WRONG_PASSWORD("Password errata"),
    ERR_NOT_LOGGED_IN("Operazione richiesta ma utente non loggato"),
    ERR_ALREADY_LOGGED_IN("Utente già loggato"),
    ERR_NOT_JOINED("Utente non partecipa alla partita corrente"),
    ERR_NO_ACTIVE_GAME("Nessuna partita attiva al momento"),
    ERR_GAME_NOT_FOUND("Partita inesistente"),
    ERR_MALFORMED("Proposta malformata (parole non valide o già usate)"),
    ERR_GAME_OVER_FOR_YOU("Hai già terminato questa partita"),
    ERR_PLAYER_NOT_FOUND("Giocatore inesistente"),
    UNKNOWN_OPERATION("Operazione sconosciuta");

    // Spiegazione umana associata al codice.
    public final String message;

    Errors(String message) {
        this.message = message;
    }
}
