package com.webhookrelay.jenkins;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebhookRelayConnection extends WebSocketClient {

    private static final Logger LOGGER = Logger.getLogger(WebhookRelayConnection.class.getName());

    private final ConnectionManager manager;

    public WebhookRelayConnection(URI serverUri, ConnectionManager manager) {
        super(serverUri);
        this.manager = manager;

        if ("wss".equals(serverUri.getScheme())) {
            // Use the JVM's default SSL socket factory, which validates the
            // server certificate chain against the default trust store.
            setSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault());
        }
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        LOGGER.info("WebSocket connection opened to Webhook Relay");
        manager.onConnected();
    }

    @Override
    public void onMessage(String message) {
        LOGGER.fine("Received message: " + message);
        manager.onMessage(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        LOGGER.info("WebSocket closed. Code: " + code + " Reason: " + reason + " Remote: " + remote);
        manager.onDisconnected(code, reason);
    }

    @Override
    public void onError(Exception ex) {
        LOGGER.log(Level.WARNING, "WebSocket error", ex);
        manager.onError(ex);
    }
}
