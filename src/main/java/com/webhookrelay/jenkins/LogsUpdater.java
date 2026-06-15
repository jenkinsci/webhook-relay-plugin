package com.webhookrelay.jenkins;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.webhookrelay.jenkins.model.ForwardResponse;
import com.webhookrelay.jenkins.model.WebhookEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LogsUpdater {

    private static final Logger LOGGER = Logger.getLogger(LogsUpdater.class.getName());
    private static final String LOGS_API_BASE = "https://my.webhookrelay.com/v1/logs/";

    private final String apiKey;
    private final String apiSecret;
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public LogsUpdater(String apiKey, String apiSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
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

            String credentials = apiKey + ":" + apiSecret;
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LOGS_API_BASE + logId))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuth)
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<Void> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (httpResponse.statusCode() == 200) {
                LOGGER.fine("Log update sent for webhook " + logId);
            } else {
                LOGGER.warning("Log update failed for webhook " + logId
                        + " - HTTP " + httpResponse.statusCode());
            }
        } catch (java.io.IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send log update", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Log update interrupted", e);
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
