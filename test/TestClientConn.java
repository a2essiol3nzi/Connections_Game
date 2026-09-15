import client.ClientConn;
import protocol.Request;
import protocol.Response;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regressioni del framing TCP del client NIO.
 */
public class TestClientConn {

    public static int run() throws Exception {
        T.section("ClientConn: UTF-8 frammentato");
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread peer = new Thread(() -> serveSplitUtf8(server, serverFailure));
            peer.start();
            Response response;
            try (ClientConn conn = new ClientConn("127.0.0.1", server.getLocalPort())) {
                Request request = new Request();
                request.operation = "probe";
                response = conn.sendAndRetreive(request);
            }
            peer.join();
            if (serverFailure.get() != null)
                throw new Exception("peer di test fallito", serverFailure.get());
            return T.cond("carattere UTF-8 diviso tra due read preservato",
                    "€".equals(response.message)) ? 0 : 1;
        }
    }

    private static void serveSplitUtf8(ServerSocket server, AtomicReference<Throwable> failure) {
        try (Socket socket = server.accept()) {
            InputStream in = socket.getInputStream();
            while (in.read() != '\n') { }
            byte[] response = "{\"status\":\"OK\",\"message\":\"€\"}\n"
                    .getBytes(StandardCharsets.UTF_8);
            int split = "{\"status\":\"OK\",\"message\":\"".getBytes(StandardCharsets.UTF_8).length + 1;
            socket.getOutputStream().write(response, 0, split);
            socket.getOutputStream().flush();
            Thread.sleep(50);
            socket.getOutputStream().write(response, split, response.length - split);
            socket.getOutputStream().flush();
        } catch (Throwable t) {
            failure.set(t);
        }
    }
}
