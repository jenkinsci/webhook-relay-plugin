package com.webhookrelay.jenkins;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.webhookrelay.jenkins.model.ForwardResponse;
import com.webhookrelay.jenkins.model.WebhookEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.util.Secret;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LogsUpdater {

    private static final Logger LOGGER = Logger.getLogger(LogsUpdater.class.getName());
    private static final URI LOGS_API_BASE = URI.create("https://my.webhookrelay.com/v1/logs/");

    private final Secret apiKey;
    private final Secret apiSecret;
    private final URI logsApiBase;
    private final Gson gson = new Gson();

    public LogsUpdater(Secret apiKey, Secret apiSecret) {
        this(apiKey, apiSecret, LOGS_API_BASE);
    }

    LogsUpdater(Secret apiKey, Secret apiSecret, URI logsApiBase) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.logsApiBase = logsApiBase;
    }

    public void sendUpdate(WebhookEvent event, ForwardResponse response) {
        if (event.getMeta() == null || event.getMeta().getId() == null || event.getMeta().getId().isEmpty()) {
            LOGGER.fine("No meta.id available, skipping log update");
            return;
        }

        try {
            String logId = event.getMeta().getId();
            String bucketId = event.getMeta().getBucketId() != null ? event.getMeta().getBucketId() : "";

            String encodedBody = "";
            if (response.getBody() != null && !response.getBody().isEmpty()) {
                encodedBody = Base64.getEncoder().encodeToString(
                        response.getBody().getBytes(StandardCharsets.UTF_8));
            }

            String status = response.getStatusCode() > 0 && response.getStatusCode() < 400
                    ? "sent" : "failed";

            LogPayload payload = new LogPayload(
                    logId,
                    bucketId,
                    encodedBody,
                    response.getStatusCode(),
                    response.getHeaders(),
                    status
            );

            String jsonPayload = gson.toJson(payload);

            String credentials = Secret.toString(apiKey) + ":" + Secret.toString(apiSecret);
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Authorization", basicAuth);

            JenkinsProxySupport.HttpResponse httpResponse = JenkinsProxySupport.send(
                    URI.create(logsApiBase + logId),
                    "PUT",
                    headers,
                    jsonPayload.getBytes(StandardCharsets.UTF_8),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(10));
            if (httpResponse.statusCode() == 200) {
                LOGGER.fine("Log update sent for webhook " + logId);
            } else {
                LOGGER.warning("Log update failed for webhook " + logId
                        + " - HTTP " + httpResponse.statusCode());
            }
        } catch (java.io.IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send log update", e);
        }
    }

    @SuppressFBWarnings(value = "URF_UNREAD_FIELD", justification = "Fields are serialized by Gson")
    private static class LogPayload {
        private final String id;
        @SerializedName("bucket_id")
        private final String bucketId;
        @SerializedName("response_body")
        private final String responseBody;
        @SerializedName("status_code")
        private final int statusCode;
        @SerializedName("response_headers")
        private final Map<String, String> responseHeaders;
        private final String status;

        LogPayload(String id, String bucketId, String responseBody,
                   int statusCode, Map<String, String> responseHeaders, String status) {
            this.id = id;
            this.bucketId = bucketId;
            this.responseBody = responseBody;
            this.statusCode = statusCode;
            this.responseHeaders = responseHeaders;
            this.status = status;
        }
    }
}
