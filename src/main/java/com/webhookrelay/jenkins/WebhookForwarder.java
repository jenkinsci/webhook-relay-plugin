package com.webhookrelay.jenkins;

import com.webhookrelay.jenkins.model.ForwardResponse;
import com.webhookrelay.jenkins.model.WebhookEvent;
import jenkins.model.Jenkins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebhookForwarder {

    private static final Logger LOGGER = Logger.getLogger(WebhookForwarder.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;

    private static final Set<String> SKIP_HEADERS = new HashSet<>(Arrays.asList(
            "host", "content-length", "transfer-encoding", "connection",
            "keep-alive", "upgrade", "proxy-authorization", "proxy-authenticate"
    ));

    /**
     * The Jenkins webhook endpoint path (e.g. "github-webhook/") to deliver to
     * when the bucket has no explicit internal output configured. Webhook Relay
     * delivers webhooks over the socket whenever a bucket has no outputs, in which
     * case the event's {@code output_destination} reflects the inbound request path
     * rather than a Jenkins URL, so we route based on the configured SCM preset.
     */
    private final String defaultEndpointPath;

    public WebhookForwarder() {
        this("generic-webhook-trigger/invoke");
    }

    public WebhookForwarder(String defaultEndpointPath) {
        this.defaultEndpointPath = (defaultEndpointPath == null || defaultEndpointPath.isEmpty())
                ? "generic-webhook-trigger/invoke"
                : defaultEndpointPath;
    }

    /**
     * Forwards the webhook event to Jenkins and returns the response.
     * Returns null if the forward could not be performed.
     */
    public ForwardResponse forward(WebhookEvent event) {
        try {
            String rootUrl = getRootUrl();
            if (rootUrl == null) {
                LOGGER.warning("Jenkins root URL is not configured. Cannot forward webhook.");
                return null;
            }

            String destination = determineDestination(event);

            String fullUrl = rootUrl;
            if (!fullUrl.endsWith("/")) {
                fullUrl += "/";
            }
            fullUrl += destination;

            if (event.getQuery() != null && !event.getQuery().isEmpty()) {
                String separator = fullUrl.contains("?") ? "&" : "?";
                fullUrl += separator + event.getQuery();
            }

            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(event.getMethod() != null ? event.getMethod() : "POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            if (event.getHeaders() != null) {
                for (Map.Entry<String, List<String>> header : event.getHeaders().entrySet()) {
                    if (header.getKey() == null || header.getValue() == null
                            || SKIP_HEADERS.contains(header.getKey().toLowerCase())) {
                        continue;
                    }
                    for (String value : header.getValue()) {
                        if (value != null) {
                            conn.addRequestProperty(header.getKey(), value);
                        }
                    }
                }
            }

            if (event.getBody() != null && !event.getBody().isEmpty()) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(event.getBody().getBytes(StandardCharsets.UTF_8));
                }
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readResponseBody(conn);
            Map<String, String> responseHeaders = extractResponseHeaders(conn);

            LOGGER.info("Forwarded webhook to " + fullUrl + " - Response: " + responseCode);

            conn.disconnect();

            return new ForwardResponse(responseCode, responseHeaders, responseBody);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to forward webhook", e);
            return null;
        }
    }

    private String readResponseBody(HttpURLConnection conn) {
        try {
            InputStream is = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                return "";
            }
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
                return baos.toString(StandardCharsets.UTF_8.name());
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not read response body", e);
            return "";
        }
    }

    private Map<String, String> extractResponseHeaders(HttpURLConnection conn) {
        Map<String, String> headers = new HashMap<>();
        Map<String, List<String>> headerFields = conn.getHeaderFields();
        if (headerFields != null) {
            for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
                String key = entry.getKey();
                if (key != null && !entry.getValue().isEmpty()) {
                    headers.put(key, String.join(", ", entry.getValue()));
                }
            }
        }
        return headers;
    }

    /**
     * Determines the Jenkins endpoint path to deliver the webhook to.
     *
     * <p>When the bucket has an explicit <b>internal output</b> configured, its
     * destination is a full URL (e.g. {@code http://jenkins:8080/github-webhook/})
     * and we honour the path from it. When the bucket has no outputs, Webhook Relay
     * still streams the webhook over the socket but {@code output_destination} holds
     * the inbound request path (e.g. {@code /}), so we route based on the configured
     * SCM preset instead.
     */
    String determineDestination(WebhookEvent event) {
        if (event.getMeta() != null
                && event.getMeta().getOutputDestination() != null) {

            String dest = event.getMeta().getOutputDestination();

            if (dest.startsWith("http://") || dest.startsWith("https://")) {
                try {
                    URL destUrl = new URL(dest);
                    dest = destUrl.getPath();
                    if (destUrl.getQuery() != null) {
                        dest += "?" + destUrl.getQuery();
                    }
                } catch (MalformedURLException e) {
                    LOGGER.fine("Could not parse output destination as URL, using as path: " + dest);
                }
                if (dest.startsWith("/")) {
                    dest = dest.substring(1);
                }
                if (!dest.isEmpty()) {
                    return dest;
                }
            }
        }

        // No explicit internal output: deliver to the configured SCM endpoint.
        return defaultEndpointPath;
    }

    protected String getRootUrl() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return null;
        }
        return jenkins.getRootUrl();
    }
}
