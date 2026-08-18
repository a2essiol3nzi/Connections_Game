package server.protocol;

import com.google.gson.JsonObject;
import server.protocol.Errors;

/**
 * Envelope di RISPOSTA dal server. Formato unico:
 *   {"status":"OK",  "payload":{...}}                       in caso di successo
 *   {"status":"ERR", "errorCode":"ERR_...", "payload":null} in caso di errore
 *
 * I dati specifici dell'operazione vanno in `payload` (JsonObject arbitrario).
 * Gli errori usano l'enum {@link Errors}: `Response.err(Errors.X)` imposta
 * `errorCode` al `name()` dell'enum (es. "ERR_USER_NOT_FOUND").
 */
public class Response {

    public String status;      // "OK" | "ERR"
    public String errorCode;   // presente solo se status == "ERR"
    public JsonObject payload;

    public Response() {}

    public Response(String status, String errorCode, JsonObject payload) {
        this.status = status;
        this.errorCode = errorCode;
        this.payload = payload;
    }

    /** Successo con payload opzionale. */
    public static Response ok(JsonObject payload) {
        return new Response("OK", null, payload);
    }

    /** Errore da enum Errors (errorCode = nome dell'enum). */
    public static Response err(Errors e) {
        return new Response("ERR", e.name(), null);
    }

    /** Errore da codice stringa grezzo (per compatibilità con risultati interni). */
    public static Response err(String code) {
        return new Response("ERR", code, null);
    }
}
