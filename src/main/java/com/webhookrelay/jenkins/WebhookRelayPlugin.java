package com.webhookrelay.jenkins;

import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Extension
public class WebhookRelayPlugin extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(WebhookRelayPlugin.class.getName());

    private static final Map<String, String> SCM_PRESETS = new LinkedHashMap<>();
    static {
        SCM_PRESETS.put("github", "github-webhook/");
        SCM_PRESETS.put("gitlab", "project/");
        SCM_PRESETS.put("bitbucket", "bitbucket-hook/");
        SCM_PRESETS.put("generic", "generic-webhook-trigger/invoke");
        SCM_PRESETS.put("custom", "");
    }

    private Secret apiKey;
    private Secret apiSecret;
    private String buckets = "";
    private boolean enabled = true;
    private String scmPreset = "github";

    private transient ConnectionManager connectionManager;
    private transient volatile ConnectionStatus connectionStatus = ConnectionStatus.DISCONNECTED;
    private transient volatile String statusMessage = "";

    public WebhookRelayPlugin() {
        load();
        if (enabled && isConfigured()) {
            startConnection();
        }
    }

    public static WebhookRelayPlugin get() {
        return GlobalConfiguration.all().get(WebhookRelayPlugin.class);
    }

    public Secret getApiKey() {
        return apiKey;
    }

    @DataBoundSetter
    public void setApiKey(Secret apiKey) {
        this.apiKey = apiKey;
    }

    public Secret getApiSecret() {
        return apiSecret;
    }

    @DataBoundSetter
    public void setApiSecret(Secret apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getBuckets() {
        return buckets;
    }

    @DataBoundSetter
    public void setBuckets(String buckets) {
        this.buckets = buckets;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getScmPreset() {
        return scmPreset;
    }

    @DataBoundSetter
    public void setScmPreset(String scmPreset) {
        this.scmPreset = scmPreset;
    }

    public ConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public String getWebhookEndpointPath() {
        String path = SCM_PRESETS.get(scmPreset);
        return path != null ? path : SCM_PRESETS.get("github");
    }

    void updateConnectionStatus(ConnectionStatus status, String message) {
        this.connectionStatus = status;
        this.statusMessage = message != null ? message : "";
    }

    public ListBoxModel doFillScmPresetItems() {
        ListBoxModel items = new ListBoxModel();
        items.add("GitHub (github-webhook/)", "github");
        items.add("GitLab (project/)", "gitlab");
        items.add("Bitbucket (bitbucket-hook/)", "bitbucket");
        items.add("Generic Webhook Trigger (generic-webhook-trigger/invoke)", "generic");
        items.add("Custom", "custom");
        return items;
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        stopConnection();

        req.bindJSON(this, json);
        save();

        if (enabled && isConfigured()) {
            startConnection();
        }
        return true;
    }

    public FormValidation doTestConnection(
            @QueryParameter("apiKey") Secret apiKey,
            @QueryParameter("apiSecret") Secret apiSecret,
            @QueryParameter("buckets") String buckets) {

        if (apiKey == null || Secret.toString(apiKey).isEmpty()) {
            return FormValidation.error("API Key is required");
        }
        if (apiSecret == null || Secret.toString(apiSecret).isEmpty()) {
            return FormValidation.error("API Secret is required");
        }

        try {
            java.net.URI uri = new java.net.URI("wss://my.webhookrelay.com/v1/socket");
            java.util.concurrent.CountDownLatch authLatch = new java.util.concurrent.CountDownLatch(1);
            final String[] result = {null};
            final boolean[] authenticated = {false};

            org.java_websocket.client.WebSocketClient testClient = new org.java_websocket.client.WebSocketClient(uri) {
                @Override
                public void onOpen(org.java_websocket.handshake.ServerHandshake handshake) {
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    com.webhookrelay.jenkins.model.AuthMessage auth =
                            new com.webhookrelay.jenkins.model.AuthMessage(
                                    Secret.toString(apiKey), Secret.toString(apiSecret));
                    send(gson.toJson(auth));
                }

                @Override
                public void onMessage(String message) {
                    try {
                        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
                        String status = json.has("status") ? json.get("status").getAsString() : "";
                        if ("authenticated".equals(status)) {
                            authenticated[0] = true;
                            result[0] = "Authenticated successfully";
                        } else {
                            String msg = json.has("message") ? json.get("message").getAsString() : status;
                            result[0] = "Server response: " + msg;
                        }
                    } catch (Exception e) {
                        result[0] = "Unexpected response: " + message;
                    }
                    authLatch.countDown();
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (result[0] == null) {
                        result[0] = "Connection closed: " + reason;
                    }
                    authLatch.countDown();
                }

                @Override
                public void onError(Exception ex) {
                    result[0] = "Connection error: " + ex.getMessage();
                    authLatch.countDown();
                }
            };

            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            testClient.setSocketFactory(sslContext.getSocketFactory());

            testClient.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS);

            if (!testClient.isOpen()) {
                return FormValidation.error("Could not connect to Webhook Relay server");
            }

            boolean completed = authLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);

            try {
                testClient.closeBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (authenticated[0]) {
                return FormValidation.ok(result[0]);
            } else if (result[0] != null) {
                return FormValidation.error(result[0]);
            } else if (!completed) {
                return FormValidation.error("Authentication timed out");
            } else {
                return FormValidation.error("Authentication failed");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Test connection failed", e);
            return FormValidation.error("Connection failed: " + e.getMessage());
        }
    }

    /**
     * Creates a Webhook Relay bucket with an input and an output pointing
     * at the selected SCM webhook endpoint on this Jenkins instance.
     */
    public FormValidation doAutoSetup(
            @QueryParameter("apiKey") Secret apiKey,
            @QueryParameter("apiSecret") Secret apiSecret,
            @QueryParameter("scmPreset") String scmPreset) {

        if (apiKey == null || Secret.toString(apiKey).isEmpty()) {
            return FormValidation.error("API Key is required");
        }
        if (apiSecret == null || Secret.toString(apiSecret).isEmpty()) {
            return FormValidation.error("API Secret is required");
        }
        if (scmPreset == null || scmPreset.isEmpty() || "custom".equals(scmPreset)) {
            return FormValidation.error("Select an SCM preset (not Custom) to auto-setup");
        }

        String endpointPath = SCM_PRESETS.get(scmPreset);
        if (endpointPath == null || endpointPath.isEmpty()) {
            return FormValidation.error("Unknown SCM preset: " + scmPreset);
        }

        try {
            WebhookRelayAPI api = new WebhookRelayAPI(
                    Secret.toString(apiKey), Secret.toString(apiSecret));

            String bucketName = "jenkins-" + scmPreset;
            WebhookRelayAPI.Bucket bucket = api.createBucket(bucketName);
            LOGGER.info("Created bucket: " + bucket.name + " (id: " + bucket.id + ")");

            WebhookRelayAPI.Input input = api.createInput(bucket.id, scmPreset + "-input");
            LOGGER.info("Created input: " + input.name + " (id: " + input.id + ")");

            String rootUrl = Jenkins.get().getRootUrl();
            if (rootUrl == null) {
                rootUrl = "http://localhost:8080/";
            }
            if (!rootUrl.endsWith("/")) {
                rootUrl += "/";
            }
            String destination = rootUrl + endpointPath;

            WebhookRelayAPI.Output output = api.createOutput(
                    bucket.id, scmPreset + "-to-jenkins", destination);
            LOGGER.info("Created output: " + output.name + " -> " + destination);

            String inputUrl = "https://my.webhookrelay.com/v1/webhooks/" + input.id;

            return FormValidation.ok(
                    "Setup complete! Bucket: " + bucketName
                            + ". Use this webhook URL in your " + scmPreset
                            + " repository settings: " + inputUrl
                            + " (output destination: " + destination + ")");
        } catch (java.io.IOException e) {
            LOGGER.log(Level.WARNING, "Auto-setup failed", e);
            return FormValidation.error("Auto-setup failed: " + e.getMessage());
        }
    }

    private boolean isConfigured() {
        return apiKey != null && !Secret.toString(apiKey).isEmpty()
                && apiSecret != null && !Secret.toString(apiSecret).isEmpty();
    }

    private List<String> parseBuckets() {
        if (buckets == null || buckets.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(buckets.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private synchronized void startConnection() {
        if (connectionManager != null) {
            connectionManager.stop();
        }
        LOGGER.info("Starting Webhook Relay connection");
        connectionManager = new ConnectionManager(
                Secret.toString(apiKey),
                Secret.toString(apiSecret),
                parseBuckets()
        );
        connectionManager.start();
    }

    synchronized void stopConnection() {
        if (connectionManager != null) {
            LOGGER.info("Stopping Webhook Relay connection");
            connectionManager.stop();
            connectionManager = null;
        }
    }
}
