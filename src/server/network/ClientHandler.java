package server.network;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import server.core.Context;
import server.core.UserStore;
import server.core.ActiveGame;
import protocol.Errors;
import protocol.Request;
import protocol.Response;
import protocol.payload.GameInfoPayload;
import protocol.payload.GameStatsPayload;
import protocol.payload.LeaderboardPayload;
import protocol.payload.PlayerStatsPayload;
import protocol.payload.SubmitPayload;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Gestisce UNA connessione client (persistente) sul pool di thread.
 *
 * Stato per-connessione: id dell'utente loggato (null se non autenticato).
 * Le risposte rispettano l'envelope Response: status OK/ERROR in cima,
 * `errorCode`/`message` in cima su errore; i dati stanno in `payload`.
 */
public class ClientHandler implements Runnable {

    private static final Gson GSON = new Gson();
    private final Socket socket;
    private final Context ctx;

    // id immutabile; accessibile solo dal thread handler
    private Integer loggedInUserId = null;

    public ClientHandler(Socket socket, Context ctx) {
        this.socket = socket;
        this.ctx = ctx;
    }

    @Override
    public void run() {
        try (
            Socket s = socket;
            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    s.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(
                    s.getOutputStream(), StandardCharsets.UTF_8))
        ) {
            String clientIp = s.getInetAddress().getHostAddress();
            String line;
            while ((line = in.readLine()) != null) {
                Request req = null;
                try {
                    req = GSON.fromJson(line, Request.class);
                } catch (JsonSyntaxException e) {
                    // JSON malformato -> prosegui senza chiudere sessione
                    out.write(GSON.toJson(Response.err(Errors.BAD_REQUEST)));
                    out.write("\n");
                    out.flush();
                    continue;
                }
                Response res = dispatch(req, clientIp);
                out.write(GSON.toJson(res));
                out.write("\n");
                out.flush();
            }
        } catch (IOException e) {
            // TCP EOF / reset: il client ha chiuso la connessione senza logout.
            // La pulizia (logout implicito) avviene nel finally qui sotto.
        } catch (Exception e) {
            // nessuna eccezione inattesa deve terminare il thread handler:
            // logga e chiude pulito senza far morire il worker del pool.
            System.err.println("[ClientHandler] eccezione inattesa: " + e);
        } finally {
            // Disconnessione per QUALSIASI causa (client chiuso, ^C/processo killed,
            // o eccezione). Il worker del pool torna subito disponibile al ritorno
            // dal run().
            String who = "non autenticato";
            if (loggedInUserId != null) {
                UserStore.User u = ctx.users.getById(loggedInUserId);
                who = (u != null) ? u.username : ("userId " + loggedInUserId);
                // logout implicito: rimuove da onlineUsers e deregistra l'endpoint UDP.
                ctx.games.logoutUser(loggedInUserId, ctx.udpRegistry);
            }
            System.out.println("[Server] client disconnesso: " + who
                + " da " + socket.getInetAddress().getHostAddress());
            loggedInUserId = null;
        }
    }

    // Richiama la logica opportuna per servire il client.
    private Response dispatch(Request req, String clientIp) {
        if (req == null || req.operation == null)
            return Response.err(Errors.BAD_REQUEST);
        // Gate auth centralizzato: operazioni che richiedono login
        boolean requiresAuth = req.operation.equals("submitProposal")
                            || req.operation.equals("requestGameInfo")
                            || req.operation.equals("requestGameStats")
                            || req.operation.equals("requestLeaderboard")
                            || req.operation.equals("requestPlayerStats");
        if ((requiresAuth) && (loggedInUserId == null))
            return Response.err(Errors.ERR_NOT_LOGGED_IN);
        // Client già loggato non può registrarsi né rifare login su un altro account. 
        // (l'account precedente resterebbe "online" per il server)
        boolean isAuth = req.operation.equals("register") || req.operation.equals("login");
        if (isAuth && loggedInUserId != null)
            return Response.err(Errors.ERR_ALREADY_LOGGED_IN);
        switch (req.operation) {
            case "register": {
                Errors r = ctx.users.register(req.username, req.psw);
                return r == null ? Response.ok(null) : Response.err(r);
            }
            case "updateCredentials": {
                Errors r = ctx.users.updateCredentials(req.oldUsername, req.oldPsw,
                                                        req.newUsername, req.newPsw);
                return r == null ? Response.ok(null) : Response.err(r);
            }
            case "login": {
                // Validazione: udpPort OBBLIGATORIO per ricevere notifiche di fine partita
                if (req.udpPort == null || req.udpPort <= 0 || req.udpPort > 65535)
                    return Response.err(Errors.BAD_REQUEST);
                Errors r = ctx.users.login(req.username, req.psw);
                if (r != null) 
                    return Response.err(r);
                // ATOMICITÀ: getByName + registerLogin serializzati rispetto a rotate()
                // Evita TOCTOU: due handler non possono loggare lo stesso utente contemporaneamente
                UserStore.User u;
                synchronized (ctx.games) {
                    u = ctx.users.getByName(req.username);
                    if (u == null)
                        return Response.err(Errors.ERR_USER_NOT_FOUND);
                    if (!ctx.games.registerLogin(u.id)) // utente loggato altrove
                        return Response.err(Errors.ERR_ALREADY_LOGGED_IN);
                }
                loggedInUserId = u.id;
                // Registra endpoint UDP ricavato da IP del client (socket TCP) e port (dal login request)
                try {
                    InetAddress addr = java.net.InetAddress.getByName(clientIp);
                    ctx.udpRegistry.register(u.id, addr, req.udpPort);
                    System.out.println("[ClientHandler] Registered UDP endpoint for userId " + u.id + 
                                        ": " + clientIp + ":" + req.udpPort);
                } catch (UnknownHostException e) {
                    System.err.println("[ClientHandler] Failed to resolve client IP " + clientIp + ": " + e);
                    ctx.games.registerLogout(u.id);
                    loggedInUserId = null;
                    return Response.err(Errors.BAD_REQUEST);
                }
                return Response.ok(ctx.games.gameInfo(u.id, -1, ctx.users));
            }
            case "logout":{
                if (loggedInUserId == null) 
                    return Response.err(Errors.ERR_NOT_LOGGED_IN);
                ctx.games.logoutUser(loggedInUserId, ctx.udpRegistry);
                loggedInUserId = null;
                return Response.ok(null);
            }
            case "submitProposal":{
                return handleProposal(req.words);
            }
            case "requestGameInfo": {
                int rid = roundIdOr(req.roundId, -1);
                GameInfoPayload info = ctx.games.gameInfo(loggedInUserId, rid, ctx.users);
                if (info == null)
                    return Response.err(rid == -1 ? Errors.ERR_NO_ACTIVE_GAME : Errors.ERR_GAME_NOT_FOUND);
                return Response.ok(info);
            }
            case "requestGameStats": {
                GameStatsPayload s = ctx.games.gameStats(roundIdOr(req.roundId, -1));
                if (s == null) return Response.err(Errors.ERR_GAME_NOT_FOUND);
                return Response.ok(s);
            }
            case "requestLeaderboard": {
                LeaderboardPayload lb = ctx.games.leaderboard(req.playerName, req.topPlayers, loggedInUserId, ctx.users);
                if (lb == null) return Response.err(Errors.ERR_PLAYER_NOT_FOUND);
                return Response.ok(lb);
            }
            case "requestPlayerStats": {
                PlayerStatsPayload ps = ctx.games.playerStats(loggedInUserId, ctx.users);
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
            SubmitPayload p = new SubmitPayload();
            p.result = r.resultLabel();
            p.game = ctx.games.gameInfo(loggedInUserId, -1, ctx.users);
            return Response.ok(p);
        }
        return Response.err(r.error());
    }

    private static int roundIdOr(Integer v, int def) { return v == null ? def : v; }
}
