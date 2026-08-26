package com.webhookrelay.jenkins;

import com.google.gson.Gson;
import com.webhookrelay.jenkins.model.ForwardResponse;
import com.webhookrelay.jenkins.model.WebhookEvent;
import com.sun.net.httpserver.HttpServer;
import hudson.ProxyConfiguration;
import hudson.util.Secret;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class JenkinsProxySupportTest {

    private Authenticator originalAuthenticator;

    @BeforeEach
    void rememberDefaultAuthenticator() {
        originalAuthenticator = Authenticator.getDefault();
    }

    @AfterEach
    void restoreDefaultAuthenticator() {
        Authenticator.setDefault(originalAuthenticator);
    }

    @Test
    void apiRequestUsesAuthenticatedProxyAndPreservesOriginAuthorization(JenkinsRule jenkins) throws Exception {
        try (AuthenticatedProxyServer proxy = new AuthenticatedProxyServer("proxy-user", "proxy-pass")) {
            jenkins.getInstance().proxy = proxyConfiguration(proxy);

            WebhookRelayAPI api = new WebhookRelayAPI(
                    Secret.fromString("relay-key"),
                    Secret.fromString("relay-secret"),
                    URI.create("http://relay.invalid/v1"));

            assertTrue(api.listBuckets().isEmpty());
            long probeRequestsAfterFirstCall = proxy.getRequests().stream()
                    .filter(request -> request.getRequestLine().startsWith("HEAD "))
                    .count();
            assertTrue(api.listBuckets().isEmpty());

            String expectedOriginAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                    "relay-key:relay-secret".getBytes(StandardCharsets.UTF_8));
            List<AuthenticatedProxyServer.Request> requests = proxy.getRequests();
            assertTrue(requests.stream().anyMatch(request -> expectedOriginAuthorization.equals(
                    request.header("Authorization"))));
            assertTrue(requests.stream().anyMatch(request -> request.header("Proxy-Authorization") != null));
            assertEquals(probeRequestsAfterFirstCall, requests.stream()
                    .filter(request -> request.getRequestLine().startsWith("HEAD "))
                    .count());
        }
    }

    @Test
    void apiRequestHonorsNoProxyHosts(JenkinsRule jenkins) throws Exception {
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/v1/buckets", exchange -> {
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        origin.start();

        try (AuthenticatedProxyServer proxy = new AuthenticatedProxyServer("proxy-user", "proxy-pass")) {
            jenkins.getInstance().proxy = new ProxyConfiguration(
                    "127.0.0.1",
                    proxy.getPort(),
                    "proxy-user",
                    "proxy-pass",
                    "127.0.0.1");
            WebhookRelayAPI api = new WebhookRelayAPI(
                    Secret.fromString("relay-key"),
                    Secret.fromString("relay-secret"),
                    URI.create("http://127.0.0.1:" + origin.getAddress().getPort() + "/v1"));

            assertTrue(api.listBuckets().isEmpty());
            assertTrue(proxy.getRequests().isEmpty());
        } finally {
            origin.stop(0);
        }
    }

    @Test
    void logUpdateUsesAuthenticatedProxy(JenkinsRule jenkins) throws Exception {
        try (AuthenticatedProxyServer proxy = new AuthenticatedProxyServer("proxy-user", "proxy-pass")) {
            jenkins.getInstance().proxy = proxyConfiguration(proxy);
            LogsUpdater updater = new LogsUpdater(
                    Secret.fromString("relay-key"),
                    Secret.fromString("relay-secret"),
                    URI.create("http://relay.invalid/v1/logs/"));
            WebhookEvent event = new Gson().fromJson(
                    "{\"meta\":{\"id\":\"log-1\",\"bucked_id\":\"bucket-1\"}}",
                    WebhookEvent.class);

            updater.sendUpdate(event, new ForwardResponse(200, Collections.emptyMap(), "ok"));

            assertTrue(proxy.getRequests().stream().anyMatch(request ->
                    request.getRequestLine().startsWith("PUT http://relay.invalid/v1/logs/log-1 ")
                            && request.header("Authorization") != null
                            && request.header("Proxy-Authorization") != null));
        }
    }

    @Test
    void webSocketConnectUsesAuthenticatedProxy(JenkinsRule jenkins) throws Exception {
        CountDownLatch serverStarted = new CountDownLatch(1);
        CountDownLatch clientOpened = new CountDownLatch(1);
        WebSocketServer server = new WebSocketServer(new InetSocketAddress("127.0.0.1", 0)) {
            @Override
            public void onOpen(WebSocket connection, ClientHandshake handshake) {
                // Nothing to send; opening the connection proves CONNECT and the handshake succeeded.
            }

            @Override
            public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            }

            @Override
            public void onMessage(WebSocket connection, String message) {
            }

            @Override
            public void onError(WebSocket connection, Exception exception) {
            }

            @Override
            public void onStart() {
                serverStarted.countDown();
            }
        };
        server.start();
        assertTrue(serverStarted.await(5, TimeUnit.SECONDS));

        try (AuthenticatedProxyServer proxy = new AuthenticatedProxyServer("proxy-user", "proxy-pass")) {
            jenkins.getInstance().proxy = proxyConfiguration(proxy);
            URI uri = URI.create("ws://127.0.0.1:" + server.getPort() + "/socket");
            WebSocketClient client = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    clientOpened.countDown();
                }

                @Override
                public void onMessage(String message) {
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                }

                @Override
                public void onError(Exception exception) {
                }
            };

            JenkinsProxySupport.configure(client, uri);
            assertTrue(client.connectBlocking(10, TimeUnit.SECONDS), () -> proxy.getRequests().toString());
            assertTrue(clientOpened.await(5, TimeUnit.SECONDS));
            client.closeBlocking();

            assertTrue(proxy.getRequests().stream().anyMatch(request ->
                    request.getRequestLine().startsWith("CONNECT 127.0.0.1:" + server.getPort())
                            && request.header("Proxy-Authorization") != null));
        } finally {
            server.stop(1_000);
        }
    }

    private static ProxyConfiguration proxyConfiguration(AuthenticatedProxyServer proxy) {
        return new ProxyConfiguration(
                "127.0.0.1",
                proxy.getPort(),
                "proxy-user",
                "proxy-pass",
                null);
    }
}
