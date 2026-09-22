package io.github.amishpr.ledger.devinfra;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Forwards a fixed local port to the embedded broker's random one. Kafka
 * clients only use the bootstrap address for their first metadata request;
 * after that they connect to the address the broker advertises, which is
 * also on localhost. So a plain byte pipe on 9092 is all it takes for every
 * service to find Kafka where Docker Compose would have put it.
 */
final class TcpForwarder implements AutoCloseable {

    private final ServerSocket server;

    TcpForwarder(int listenPort, int targetPort) throws IOException {
        this.server = new ServerSocket(listenPort, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("forward-" + listenPort).start(() -> acceptLoop(targetPort));
    }

    private void acceptLoop(int targetPort) {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket upstream = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                Thread.ofVirtual().start(() -> pipe(client, upstream));
                Thread.ofVirtual().start(() -> pipe(upstream, client));
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private static void pipe(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException ignored) {
            // One side closed; the finally below tears down the other.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
