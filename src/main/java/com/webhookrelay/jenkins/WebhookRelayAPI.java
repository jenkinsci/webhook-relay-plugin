package com.webhookrelay.jenkins;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

public class WebhookRelayAPI {

    private static final String API_BASE = "https://my.webhookrelay.com/v1";
    static final String PUBLIC_WEBHOOK_BASE = "https://my.webhookrelay.com/v1/webhooks/";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String apiKey;
    private final String apiSecret;
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

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
        String credentials = apiKey + ":" + apiSecret;
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", basicAuth)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new IOException("API request failed: HTTP " + response.statusCode()
                        + " - " + response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("API request was interrupted", e);
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
