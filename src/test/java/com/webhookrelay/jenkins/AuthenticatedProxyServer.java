package com.webhookrelay.jenkins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** A small Basic-auth HTTP proxy used by the proxy integration tests. */
final class AuthenticatedProxyServer implements AutoCloseable {

    private final String expectedAuthorization;
    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running = true;

    AuthenticatedProxyServer(String username, String password) throws IOException {
        expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.ISO_8859_1));
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        executor.execute(this::acceptConnections);
    }

    int getPort() {
        return serverSocket.getLocalPort();
    }

    List<Request> getRequests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    private void acceptConnections() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                executor.execute(() -> handle(socket));
            } catch (IOException e) {
                if (running) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setSoTimeout(10_000);
            String headerBlock = readHeaderBlock(client.getInputStream());
            Request request = Request.parse(headerBlock);
            requests.add(request);

            if (!expectedAuthorization.equals(request.header("Proxy-Authorization"))) {
                writeResponse(client.getOutputStream(),
                        "HTTP/1.1 407 Proxy Authentication Required\r\n"
                                + "Proxy-Authenticate: Basic realm=\"test-proxy\"\r\n"
                                + "Content-Length: 0\r\n"
                                + "Connection: close\r\n\r\n");
                return;
            }

            if (request.requestLine.startsWith("CONNECT ")) {
                tunnel(client, request.requestLine.split(" ")[1]);
                return;
            }

            String body = request.requestLine.contains("/v1/buckets") ? "[]" : "{}";
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            writeResponse(client.getOutputStream(),
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + bodyBytes.length + "\r\n"
                            + "Connection: close\r\n\r\n"
                            + body);
        } catch (IOException ignored) {
            // A client can close a challenged connection before reading the body.
        }
    }

    private void tunnel(Socket client, String authority) throws IOException {
        int colon = authority.lastIndexOf(':');
        String host = authority.substring(0, colon);
        int port = Integer.parseInt(authority.substring(colon + 1));
        Socket upstream = new Socket();
        upstream.connect(new InetSocketAddress(host, port), 5_000);
        writeResponse(client.getOutputStream(), "HTTP/1.1 200 Connection Established\r\n\r\n");

        executor.execute(() -> copy(client, upstream));
        copy(upstream, client);
        upstream.close();
    }

    private static void copy(Socket source, Socket destination) {
        try {
            source.getInputStream().transferTo(destination.getOutputStream());
        } catch (IOException ignored) {
            // Closing either side terminates the tunnel.
        }
    }

    private static String readHeaderBlock(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        int value;
        while ((value = input.read()) != -1) {
            output.write(value);
            if ((matched == 0 || matched == 2) && value == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && value == '\n') {
                matched++;
            } else {
                matched = value == '\r' ? 1 : 0;
            }
            if (matched == 4) {
                return output.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new IOException("Connection closed before request headers were complete");
    }

    private static void writeResponse(OutputStream output, String response) throws IOException {
        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    @Override
    public void close() throws Exception {
        running = false;
        serverSocket.close();
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    static final class Request {
        private final String requestLine;
        private final List<String> headers;

        private Request(String requestLine, List<String> headers) {
            this.requestLine = requestLine;
            this.headers = headers;
        }

        static Request parse(String headerBlock) {
            String[] lines = headerBlock.split("\\r\\n");
            List<String> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                headers.add(lines[i]);
            }
            return new Request(lines[0], headers);
        }

        String getRequestLine() {
            return requestLine;
        }

        @Override
        public String toString() {
            return requestLine + " " + headers;
        }

        String header(String name) {
            String prefix = name.toLowerCase(Locale.ROOT) + ":";
            for (String header : headers) {
                if (header.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    return header.substring(header.indexOf(':') + 1).trim();
                }
            }
            return null;
        }
    }
}
