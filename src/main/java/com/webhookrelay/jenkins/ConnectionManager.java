package com.webhookrelay.jenkins;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.webhookrelay.jenkins.model.AuthMessage;
import com.webhookrelay.jenkins.model.ForwardResponse;
import com.webhookrelay.jenkins.model.SubscribeMessage;
import com.webhookrelay.jenkins.model.WebhookEvent;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConnectionManager {

    private static final Logger LOGGER = Logger.getLogger(ConnectionManager.class.getName());
    private static final String WS_ENDPOINT = "wss://my.webhookrelay.com/v1/socket";

    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 5 * 60 * 1000;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final String apiKey;
    private final String apiSecret;
    private final List<String> buckets;
    private final WebhookForwarder forwarder;
    private final LogsUpdater logsUpdater;
    private final WebhookRelayPlugin plugin;
    private final Gson gson = new Gson();

    private volatile WebhookRelayConnection connection;
    private volatile boolean running = false;
    private volatile long currentBackoff = INITIAL_BACKOFF_MS;
    private volatile boolean authFailed = false;

    private ScheduledExecutorService scheduler;

    public ConnectionManager(WebhookRelayPlugin plugin, String apiKey, String apiSecret,
                             List<String> buckets, String endpointPath) {
        this.plugin = plugin;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.buckets = buckets;
        this.forwarder = new WebhookForwarder(endpointPath);
        this.logsUpdater = new LogsUpdater(apiKey, apiSecret);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        authFailed = false;
        currentBackoff = INITIAL_BACKOFF_MS;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "webhook-relay-connection-manager");
            t.setDaemon(true);
            return t;
        });
        connect();
    }

    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (connection != null) {
            try {
                if (connection.isOpen()) {
                    connection.close();
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Error closing WebSocket connection", e);
            }
            connection = null;
        }
        updateStatus(ConnectionStatus.DISCONNECTED, "");
    }

    public boolean isRunning() {
        return running;
    }

    private void connect() {
        if (!running || authFailed) {
            return;
        }
        updateStatus(ConnectionStatus.CONNECTING, "");

        try {
            URI uri = new URI(WS_ENDPOINT);
            connection = new WebhookRelayConnection(uri, this);
            connection.setConnectionLostTimeout(60);
            connection.connect();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to initiate WebSocket connection", e);
            updateStatus(ConnectionStatus.ERROR, e.getMessage());
            scheduleReconnect();
        }
    }

    void onConnected() {
        currentBackoff = INITIAL_BACKOFF_MS;
        updateStatus(ConnectionStatus.CONNECTED, "");

        AuthMessage auth = new AuthMessage(apiKey, apiSecret);
        connection.send(gson.toJson(auth));
    }

    void onMessage(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();

            String type = json.has("type") ? json.get("type").getAsString() : null;

            if ("status".equals(type)) {
                handleStatusMessage(json);
            } else if ("webhook".equals(type)) {
                WebhookEvent event = gson.fromJson(message, WebhookEvent.class);
                LOGGER.info("Received webhook for bucket: " +
                        (event.getMeta() != null ? event.getMeta().getBucketName() : "unknown"));
                ForwardResponse response = forwarder.forward(event);
                if (response != null) {
                    logsUpdater.sendUpdate(event, response);
                }
            } else {
                LOGGER.fine("Received unknown message type: " + type);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing message: " + message, e);
        }
    }

    private void handleStatusMessage(JsonObject json) {
        String status = json.has("status") ? json.get("status").getAsString() : "";
        String msg = json.has("message") ? json.get("message").getAsString() : "";

        switch (status) {
            case "authenticated":
                LOGGER.info("Authenticated with Webhook Relay");
                updateStatus(ConnectionStatus.AUTHENTICATED, msg);

                SubscribeMessage sub = new SubscribeMessage(buckets.isEmpty() ? null : buckets);
                connection.send(gson.toJson(sub));
                break;

            case "subscribed":
                LOGGER.info("Subscribed to buckets: " + msg);
                updateStatus(ConnectionStatus.SUBSCRIBED, msg);
                break;

            default:
                if (status.contains("error") || status.contains("fail")) {
                    LOGGER.warning("Webhook Relay error: " + status + " - " + msg);
                    if (status.contains("auth") || msg.toLowerCase().contains("unauthorized")) {
                        authFailed = true;
                        updateStatus(ConnectionStatus.AUTH_FAILED, msg);
                    } else {
                        updateStatus(ConnectionStatus.ERROR, msg);
                    }
                } else {
                    LOGGER.info("Webhook Relay status: " + status + " - " + msg);
                }
                break;
        }
    }

    void onDisconnected(int code, String reason) {
        if (running && !authFailed) {
            LOGGER.info("WebSocket disconnected: " + reason + ". Scheduling reconnect.");
            updateStatus(ConnectionStatus.DISCONNECTED, reason);
            scheduleReconnect();
        }
    }

    void onError(Exception ex) {
        LOGGER.log(Level.WARNING, "WebSocket error", ex);
        updateStatus(ConnectionStatus.ERROR, ex.getMessage());
    }

    private void scheduleReconnect() {
        if (!running || authFailed || scheduler == null || scheduler.isShutdown()) {
            return;
        }
        LOGGER.info("Reconnecting in " + currentBackoff + "ms");
        scheduler.schedule(this::connect, currentBackoff, TimeUnit.MILLISECONDS);
        currentBackoff = Math.min((long) (currentBackoff * BACKOFF_MULTIPLIER), MAX_BACKOFF_MS);
    }

    private void updateStatus(ConnectionStatus status, String message) {
        // Use the plugin reference captured at construction time rather than
        // WebhookRelayPlugin.get(), which would re-enter Guice provisioning and
        // cause a circular-dependency error when the connection is started from
        // the plugin's constructor.
        if (plugin != null) {
            plugin.updateConnectionStatus(status, message);
        }
    }
}
