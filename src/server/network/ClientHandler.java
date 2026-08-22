package server.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import server.core.Context;
import server.core.UserStore;
import server.core.ActiveGame;
import server.protocol.Errors;
import server.protocol.Request;
import server.protocol.Response;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Gestisce UNA connessione client (persistente) sul pool di thread.
 *
 * Stato per-connessione: id dell'utente loggato (null se non autenticato).
 * Tutte le risposte rispettano l'envelope Response: status OK/ERROR in cima,
 * `errorCode`/`message` in cima su errore; i dati stanno in `payload`.
 */
public class ClientHandler implements Runnable {

    private static final Gson GSON = new Gson();
    private final Socket socket;
    private final Context ctx;

    private Integer loggedInUserId = null; // id immutabile; accessibile solo dal thread handler

    public ClientHandler(Socket socket, Context ctx) {
        this.socket = socket;
        this.ctx = ctx;
    }

    @Override
    public void run() {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = in.readLine()) != null) {
                Request req = GSON.fromJson(line, Request.class);
                Response res = dispatch(req);
                out.write(GSON.toJson(res));
                out.write("\n");
                out.flush();
            }
        } catch (IOException e) {
            // client disconnesso
        } catch (Exception e) {
            // nessuna eccezione inattesa deve terminare il thread handler:
            // logga e chiude pulito senza far morire il worker del pool.
            System.err.println("[ClientHandler] eccezione inattesa: " + e);
        } finally {
            // logout implicito alla chiusura: rimuove dal registry online (B3).
            if (loggedInUserId != null) 
                ctx.games.registerLogout(loggedInUserId);
            loggedInUserId = null;
        }
    }

    // Ricava risposta da inviare al client.
    private Response dispatch(Request req) {
        if (req == null || req.operation == null)
            return Response.err(Errors.BAD_REQUEST);

        switch (req.operation) {
            case "register": {
                Errors r = ctx.users.register(req.username, req.psw);
                return r == null ? Response.ok(new JsonObject()) : Response.err(r);
            }
            case "updateCredentials": {
                Errors r = ctx.users.updateCredentials(req.oldUsername, req.oldPsw,
                                                        req.newUsername, req.newPsw);
                return r == null ? Response.ok(new JsonObject()) : Response.err(r);
            }
            case "login": {
                Errors r = ctx.users.login(req.username, req.psw);
                if (r != null) 
                    return Response.err(r);
                UserStore.User u = ctx.users.getByName(req.username);
                if (ctx.games.isOnline(u.id))
                    return Response.err(Errors.ERR_ALREADY_LOGGED_IN);
                loggedInUserId = u.id;
                ctx.games.registerLogin(u.id); // registra online + join partita corrente
                return Response.ok(ctx.games.gameInfo(u.id, -1, ctx.users));
            }
            case "logout":{
                if (loggedInUserId == null) 
                    return Response.err(Errors.ERR_NOT_LOGGED_IN);
                ctx.games.registerLogout(loggedInUserId);
                loggedInUserId = null;
                return Response.ok(new JsonObject());
            }
            case "submitProposal":{
                if (loggedInUserId == null) 
                    return Response.err(Errors.ERR_NOT_LOGGED_IN);
                return handleProposal(req.words);
            }
            case "requestGameInfo": {
                if (loggedInUserId == null) return Response.err(Errors.ERR_NOT_LOGGED_IN);
                int rid = gameIdOr(req.gameId, -1);
                JsonObject info = ctx.games.gameInfo(loggedInUserId, rid, ctx.users);
                if (info == null)
                    return Response.err(rid == -1 ? Errors.ERR_NO_ACTIVE_GAME : Errors.ERR_GAME_NOT_FOUND);
                return Response.ok(info);
            }
            case "requestGameStats": {
                if (loggedInUserId == null) return Response.err(Errors.ERR_NOT_LOGGED_IN);
                JsonObject s = ctx.games.gameStats(gameIdOr(req.gameId, -1));
                if (s == null) return Response.err(Errors.ERR_GAME_NOT_FOUND);
                return Response.ok(s);
            }
            case "requestLeaderboard": {
                if (loggedInUserId == null) return Response.err(Errors.ERR_NOT_LOGGED_IN);
                JsonObject lb = ctx.games.leaderboard(req.playerName, req.topPlayers, ctx.users);
                if (lb == null) return Response.err(Errors.ERR_PLAYER_NOT_FOUND);
                return Response.ok(lb);
            }
            case "requestPlayerStats": {
                if (loggedInUserId == null) return Response.err(Errors.ERR_NOT_LOGGED_IN);
                JsonObject ps = ctx.games.playerStats(loggedInUserId, ctx.users);
                if (ps == null) return Response.err(Errors.ERR_USER_NOT_FOUND);
                return Response.ok(ps);
            }
            default: {
                return Response.err(Errors.UNKNOWN_OPERATION);
            }
        }
    }

    private Response handleProposal(List<String> words) {
        ActiveGame.SubmitResult r = ctx.games.submitProposal(loggedInUserId, words);
        if (r.isOk()) {
            JsonObject p = new JsonObject();
            p.addProperty("result", r.resultLabel());
            p.add("game", ctx.games.gameInfo(loggedInUserId, -1, ctx.users));
            return Response.ok(p);
        }
        return Response.err(r.error()); // mappato 1:1 sull'enum Errors
    }

    private static int gameIdOr(Integer v, int def) { return v == null ? def : v; }
}
