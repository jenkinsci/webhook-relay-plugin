package com.webhookrelay.jenkins;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WebhookRelayAPI {

    private static final Logger LOGGER = Logger.getLogger(WebhookRelayAPI.class.getName());
    private static final String API_BASE = "https://my.webhookrelay.com/v1";
    static final String PUBLIC_WEBHOOK_BASE = "https://my.webhookrelay.com/v1/webhooks/";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final String apiKey;
    private final String apiSecret;
    private final Gson gson = new Gson();

    public WebhookRelayAPI(String apiKey, String apiSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    public List<Bucket> listBuckets() throws IOException {
        String response = doRequest("GET", "/buckets", null);
        return gson.fromJson(response, new TypeToken<List<Bucket>>() {}.getType());
    }

    public Bucket getBucket(String idOrName) throws IOException {
        String response = doRequest("GET", "/buckets/" + idOrName, null);
        return gson.fromJson(response, Bucket.class);
    }

    /**
     * Returns the bucket matching the given name (or id), or {@code null} if none exists.
     */
    public Bucket findBucketByName(String name) throws IOException {
        for (Bucket bucket : listBuckets()) {
            if (name.equals(bucket.name) || name.equals(bucket.id)) {
                return bucket;
            }
        }
        return null;
    }

    /**
     * Finds an existing bucket by name, creating it (with its default public input)
     * if it does not exist. The returned bucket is always re-fetched so that the
     * auto-created input (and its public endpoint URL) is populated.
     */
    public Bucket findOrCreateBucket(String name) throws IOException {
        Bucket bucket = findBucketByName(name);
        if (bucket == null) {
            bucket = createBucket(name);
        }
        // Re-fetch to ensure inputs (and their custom domains) are populated.
        return getBucket(bucket.id);
    }

    public Bucket createBucket(String name) throws IOException {
        Bucket bucket = new Bucket();
        bucket.name = name;
        String response = doRequest("POST", "/buckets", gson.toJson(bucket));
        return gson.fromJson(response, Bucket.class);
    }

    public Input createInput(String bucketId, String name) throws IOException {
        Input input = new Input();
        input.name = name;
        String response = doRequest("POST", "/buckets/" + bucketId + "/inputs", gson.toJson(input));
        return gson.fromJson(response, Input.class);
    }

    public Output createOutput(String bucketId, String name, String destination) throws IOException {
        Output output = new Output();
        output.name = name;
        output.destination = destination;
        output.internal = true;
        String response = doRequest("POST", "/buckets/" + bucketId + "/outputs", gson.toJson(output));
        return gson.fromJson(response, Output.class);
    }

    private String doRequest(String method, String path, String body) throws IOException {
        URL url = new URL(API_BASE + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json");

        String credentials = apiKey + ":" + apiSecret;
        conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8)));

        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int responseCode = conn.getResponseCode();
        String responseBody = readBody(conn);

        conn.disconnect();

        if (responseCode >= 400) {
            throw new IOException("API request failed: HTTP " + responseCode + " - " + responseBody);
        }

        return responseBody;
    }

    private String readBody(HttpURLConnection conn) throws IOException {
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
    }

    public static class Bucket {
        public String id;
        public String name;
        public List<Input> inputs;
        public List<Output> outputs;

        /**
         * Returns the input to advertise as the public webhook endpoint, preferring
         * one that already has a dedicated {@code *.hooks.webhookrelay.com} domain.
         */
        public Input primaryInput() {
            if (inputs == null || inputs.isEmpty()) {
                return null;
            }
            for (Input input : inputs) {
                if (input.customDomain != null && !input.customDomain.isEmpty()) {
                    return input;
                }
            }
            return inputs.get(0);
        }
    }

    public static class Input {
        public String id;
        public String name;
        @SerializedName("bucket_id")
        public String bucketId;
        @SerializedName("custom_domain")
        public String customDomain;
        @SerializedName("path_prefix")
        public String pathPrefix;

        /**
         * The public webhook URL to paste into your SCM provider. Prefers the
         * dedicated {@code *.hooks.webhookrelay.com} domain when available, falling
         * back to the canonical {@code /v1/webhooks/<id>} endpoint.
         */
        public String endpointUrl() {
            if (customDomain != null && !customDomain.isEmpty()) {
                return "https://" + customDomain + (pathPrefix != null ? pathPrefix : "");
            }
            return PUBLIC_WEBHOOK_BASE + id;
        }
    }

    public static class Output {
        public String id;
        public String name;
        @SerializedName("bucket_id")
        public String bucketId;
        public String destination;
        public boolean internal;
    }
}
