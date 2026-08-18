package server.protocol;

/**
 * Codici di errore CENTRALIZZATI (§5: "Specificare correttamente codici di errore").
 *
 * Tutte le risposte di errore del server passano da qui: niente stringhe
 * hardcoded sparse nel codice. `name()` è il codice inviato al client
 * (es. "ERR_USER_NOT_FOUND"); `message` è la spiegazione umana (lato server /
 * debug client). Un metodo che ritorna `null` indica successo ("OK").
 */
public enum Errors {

    ERR_INVALID("Richiesta non valida (campi mancanti/vuoti)"),
    ERR_USER_NOT_FOUND("Utente inesistente"),
    ERR_USERNAME_TAKEN("Username già registrato"),
    ERR_WRONG_PASSWORD("Password errata"),
    ERR_NOT_LOGGED_IN("Operazione richiesta ma utente non loggato"),
    ERR_NOT_JOINED("Utente non partecipa alla partita corrente"),
    ERR_NO_ACTIVE_GAME("Nessuna partita attiva al momento"),
    ERR_GAME_NOT_FOUND("Partita inesistente"),
    ERR_MALFORMED("Proposta malformata (parole non valide o già usate)"),
    ERR_GAME_OVER_FOR_YOU("Hai già terminato questa partita"),
    UNKNOWN_OPERATION("Operazione sconosciuta");

    /** Spiegazione umana associata al codice. */
    public final String message;

    Errors(String message) {
        this.message = message;
    }
}
