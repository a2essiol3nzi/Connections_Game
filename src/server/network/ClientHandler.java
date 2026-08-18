package server.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import server.core.Context;
import server.protocol.Request;
import server.protocol.Response;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Gestisce UNA connessione client (persistente) sul pool di thread.
 * Protocollo line-based: ogni messaggio è una riga JSON terminata da '\n'.
 * (Il server usa I/O bloccante; il vincolo NIO è lato CLIENT, §3.)
 *
 * Stato per-connessione: username loggato (null se non autenticato).
 * Pacchetto `network`: tutto ciò che riguarda la comunicazione socket.
 */
public class ClientHandler implements Runnable {

    private static final Gson GSON = new Gson();
    private final Socket socket;
    private final Context ctx;

    private String loggedInUser = null; // accessibile solo dal thread handler

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
        } finally {
            loggedInUser = null; // logout implicito alla chiusura
        }
    }

    private Response dispatch(Request req) {
        if (req == null || req.operation == null)
            return Response.err("BAD_REQUEST");

        switch (req.operation) {
            case "register": {
                String r = ctx.users.register(req.username, req.psw);
                return r == null ? Response.ok(new JsonObject()) : Response.err(r);
            }
            case "updateCredentials": {
                String r = ctx.users.updateCredentials(req.oldUsername, req.oldPsw,
                        req.newUsername, req.newPsw);
                return r == null ? Response.ok(new JsonObject()) : Response.err(r);
            }
            case "login": {
                // login può ritornare un errore stringa o null (=OK)
                String r = ctx.users.login(req.username, req.psw);
                if (r != null) return Response.err(r); // r è un codice ERR_... o null
                loggedInUser = req.username;
                ctx.games.join(req.username); // auto-join partita corrente
                return Response.ok(ctx.games.gameInfo(req.username, -1));
            }
            case "logout":
                if (loggedInUser == null) return Response.err("ERR_NOT_LOGGED_IN");
                loggedInUser = null;
                return Response.ok(new JsonObject());
            case "submitProposal":
                if (loggedInUser == null) return Response.err("ERR_NOT_LOGGED_IN");
                return handleProposal(req.words);
            case "requestGameInfo":
                if (loggedInUser == null) return Response.err("ERR_NOT_LOGGED_IN");
                return Response.ok(ctx.games.gameInfo(loggedInUser, gameIdOr(req.gameId, -1)));
            case "requestGameStats":
                if (loggedInUser == null) return Response.err("ERR_NOT_LOGGED_IN");
                return Response.ok(ctx.games.gameStats(gameIdOr(req.gameId, -1)));
            case "requestLeaderboard":
                if (loggedInUser == null) return Response.err("ERR_NOT_LOGGED_IN");
                return Response.ok(ctx.games.leaderboard(req.playerName, req.topPlayers, ctx.users));
            case "requestPlayerStats":
                if (loggedInUser == null) return Response.err("ERR_NOT_LOGGED_IN");
                return Response.ok(ctx.games.playerStats(loggedInUser, ctx.users));
            default:
                return Response.err("UNKNOWN_OPERATION");
        }
    }

    private Response handleProposal(java.util.List<String> words) {
        String r = ctx.games.submitProposal(loggedInUser, words);
        JsonObject p = new JsonObject();
        if (r == null) return Response.err("ERR_NO_ACTIVE_GAME");
        switch (r) {
            case "OK_FOUND":
                p.addProperty("result", "CORRECT");
                p.add("game", ctx.games.gameInfo(loggedInUser, -1));
                return Response.ok(p);
            case "OK_WRONG":
                p.addProperty("result", "WRONG");
                p.add("game", ctx.games.gameInfo(loggedInUser, -1));
                return Response.ok(p);
            case "ERR_MALFORMED": return Response.err("ERR_MALFORMED");
            case "ERR_FINISHED":  return Response.err("ERR_GAME_OVER_FOR_YOU");
            case "ERR_NOTJOINED": return Response.err("ERR_NOT_JOINED");
            default: return Response.err(r); // errori stile ERR_* diretti
        }
    }

    private static int gameIdOr(Integer v, int def) { return v == null ? def : v; }
}
