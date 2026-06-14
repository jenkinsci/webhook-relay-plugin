package com.webhookrelay.jenkins;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.webhookrelay.jenkins.model.ForwardResponse;
import com.webhookrelay.jenkins.model.WebhookEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LogsUpdater {

    private static final Logger LOGGER = Logger.getLogger(LogsUpdater.class.getName());
    private static final String LOGS_API_BASE = "https://my.webhookrelay.com/v1/logs/";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final String apiKey;
    private final String apiSecret;
    private final Gson gson = new Gson();

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

            URL url = new URL(LOGS_API_BASE + logId);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");

            String credentials = apiKey + ":" + apiSecret;
            String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", basicAuth);

            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                LOGGER.fine("Log update sent for webhook " + logId);
            } else {
                LOGGER.warning("Log update failed for webhook " + logId
                        + " - HTTP " + responseCode);
            }

            conn.disconnect();
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
