package protocol;

/**
 * Envelope di RISPOSTA dal server. Formato unico:
 *   {"status":"OK",     "payload":{...}}                                        in caso di successo
 *   {"status":"ERROR",  "errorCode":"ERR_...", "message":"...", "payload":null} in caso di errore
 *
 * `payload` è l'oggetto specifico dell'operazione (un POJO di `protocol.payload`): 
 * il tipo del payload è determinato dall'operazione della richiesta. 
 * MAI un `errorCode` annidato nel payload. Gli errori usano l'enum Errors: `Response.err(Errors.X)` 
 * imposta `errorCode`=name() e `message`=message() dell'enum.
 */
public class Response {

    public String status;      // "OK" | "ERROR"
    public String errorCode;   // presente solo se status == "ERROR"
    public String message;     // spiegazione umana (da Errors), solo su ERROR
    public Object payload;     // POJO del payload

    public Response() {}

    public Response(String status, String errorCode, String message, Object payload) {
        this.status = status;
        this.errorCode = errorCode;
        this.message = message;
        this.payload = payload;
    }

    // Successo con payload opzionale (un POJO di protocol.payload, o null/empty).
    public static Response ok(Object payload) {
        return new Response("OK", null, null, payload);
    }

    // Errore da enum Errors (errorCode = nome, message = testo).
    public static Response err(Errors e) {
        return new Response("ERROR", e.name(), e.message, null);
    }

    // Errore da codice stringa grezzo (esiti interni stile ERR_*).
    public static Response err(String code) {
        return new Response("ERROR", code, null, null);
    }
}