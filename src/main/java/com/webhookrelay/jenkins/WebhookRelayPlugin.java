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
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

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
    private transient volatile String bucketId;

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

    /**
     * The Webhook Relay dashboard URL for the connected bucket (logs and settings),
     * or {@code null} until the bucket has been resolved (via Get Webhook URL or the
     * first received webhook).
     */
    public String getBucketDetailsUrl() {
        return (bucketId != null && !bucketId.isEmpty())
                ? "https://my.webhookrelay.com/buckets/" + bucketId : null;
    }

    void setBucketId(String bucketId) {
        if (bucketId != null && !bucketId.isEmpty()) {
            this.bucketId = bucketId;
        }
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

            testClient.setSocketFactory(
                    (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault());

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
     * Looks up the named Webhook Relay bucket (creating it with its default public
     * input if it does not exist) and returns the public webhook URL to paste into
     * the SCM provider's webhook settings. The bucket is left without outputs so that
     * webhooks stream over the socket to this plugin and every request is recorded on
     * the bucket's logs page.
     */
    @POST
    public void doResolveWebhookUrl(
            StaplerResponse2 rsp,
            @QueryParameter("apiKey") Secret apiKey,
            @QueryParameter("apiSecret") Secret apiSecret,
            @QueryParameter("buckets") String buckets,
            @QueryParameter("scmPreset") String scmPreset) throws java.io.IOException {

        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        JSONObject result = resolveWebhookUrl(apiKey, apiSecret, buckets, scmPreset);
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.getWriter().write(result.toString());
    }

    private JSONObject resolveWebhookUrl(Secret apiKey, Secret apiSecret, String buckets, String scmPreset) {
        JSONObject result = new JSONObject();
        if (apiKey == null || Secret.toString(apiKey).isEmpty()) {
            return result.element("ok", false).element("error", "API Key is required");
        }
        if (apiSecret == null || Secret.toString(apiSecret).isEmpty()) {
            return result.element("ok", false).element("error", "API Secret is required");
        }

        String bucketName = firstBucketName(buckets);
        if (bucketName == null) {
            return result.element("ok", false).element("error", "Enter a bucket name first");
        }

        try {
            WebhookRelayAPI api = new WebhookRelayAPI(
                    Secret.toString(apiKey), Secret.toString(apiSecret));

            WebhookRelayAPI.Bucket bucket = api.findOrCreateBucket(bucketName);
            WebhookRelayAPI.Input input = bucket.primaryInput();
            if (input == null) {
                return result.element("ok", false).element("error",
                        "Bucket '" + bucketName + "' has no input endpoint. "
                                + "Add a public input at https://my.webhookrelay.com/buckets");
            }

            setBucketId(bucket.id);

            String preset = (scmPreset == null || scmPreset.isEmpty()) ? "github" : scmPreset;
            String endpointPath = SCM_PRESETS.getOrDefault(preset, SCM_PRESETS.get("github"));
            String inputUrl = input.endpointUrl();
            LOGGER.info("Resolved webhook URL for bucket '" + bucketName + "': " + inputUrl);

            return result.element("ok", true)
                    .element("url", inputUrl)
                    .element("preset", preset)
                    .element("endpointPath", endpointPath.isEmpty() ? "(custom output)" : endpointPath)
                    .element("bucketUrl", getBucketDetailsUrl() != null ? getBucketDetailsUrl() : "");
        } catch (java.io.IOException e) {
            LOGGER.log(Level.WARNING, "Failed to resolve webhook URL", e);
            return result.element("ok", false)
                    .element("error", "Failed to resolve webhook URL: " + e.getMessage());
        }
    }

    private String firstBucketName(String buckets) {
        if (buckets == null) {
            return null;
        }
        for (String part : buckets.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
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
                this,
                Secret.toString(apiKey),
                Secret.toString(apiSecret),
                parseBuckets(),
                getWebhookEndpointPath()
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
