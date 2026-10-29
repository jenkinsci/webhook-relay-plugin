package com.webhookrelay.jenkins;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import hudson.util.Secret;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WebhookRelayAPI {

    private static final URI API_BASE = URI.create("https://my.webhookrelay.com/v1");
    static final String PUBLIC_WEBHOOK_BASE = "https://my.webhookrelay.com/v1/webhooks/";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Secret apiKey;
    private final Secret apiSecret;
    private final URI apiBase;
    private final Gson gson = new Gson();

    public WebhookRelayAPI(Secret apiKey, Secret apiSecret) {
        this(apiKey, apiSecret, API_BASE);
    }

    WebhookRelayAPI(Secret apiKey, Secret apiSecret, URI apiBase) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.apiBase = apiBase;
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

    public List<Function> listFunctions() throws IOException {
        String response = doRequest("GET", "/functions", null);
        List<Function> functions = gson.fromJson(response, new TypeToken<List<Function>>() {}.getType());
        return functions != null ? functions : new java.util.ArrayList<>();
    }

    /** Creates a function. The source is sent base64-encoded, as the Go SDK does. */
    public Function createFunction(String name, String driver, String source) throws IOException {
        String response = doRequest("POST", "/functions", functionRequestBody(name, driver, source));
        return gson.fromJson(response, Function.class);
    }

    public Function updateFunction(String id, String name, String driver, String source) throws IOException {
        String response = doRequest("PUT", "/functions/" + id, functionRequestBody(name, driver, source));
        return gson.fromJson(response, Function.class);
    }

    static String functionRequestBody(String name, String driver, String source) {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("driver", driver);
        body.addProperty("payload", Base64.getEncoder().encodeToString(
                source.getBytes(StandardCharsets.UTF_8)));
        return body.toString();
    }

    /** Sets (creates or overwrites) a function configuration variable. */
    public void setFunctionConfig(String functionId, String key, String value) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("key", key);
        body.addProperty("value", value);
        doRequest("PUT", "/functions/" + functionId + "/config", body.toString());
    }

    /**
     * Attaches a function to an input (or detaches it when {@code functionId} is
     * empty). The input update API replaces the whole input, so the current
     * input is re-read and sent back with only {@code function_id} changed.
     */
    public void setInputFunction(String bucketId, String inputId, String functionId) throws IOException {
        JsonObject bucket = JsonParser.parseString(doRequest("GET", "/buckets/" + bucketId, null))
                .getAsJsonObject();
        JsonObject input = findInput(bucket, inputId);
        if (input == null) {
            throw new IOException("Input " + inputId + " not found in bucket " + bucketId);
        }
        input.addProperty("function_id", functionId);
        doRequest("PUT", "/buckets/" + bucketId + "/inputs/" + inputId, input.toString());
    }

    private static JsonObject findInput(JsonObject bucket, String inputId) {
        JsonElement inputs = bucket.get("inputs");
        if (inputs == null || !inputs.isJsonArray()) {
            return null;
        }
        JsonArray array = inputs.getAsJsonArray();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                JsonElement id = element.getAsJsonObject().get("id");
                if (id != null && !id.isJsonNull() && inputId.equals(id.getAsString())) {
                    return element.getAsJsonObject();
                }
            }
        }
        return null;
    }

    private String doRequest(String method, String path, String body) throws IOException {
        String credentials = Secret.toString(apiKey) + ":" + Secret.toString(apiSecret);
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", basicAuth);

        JenkinsProxySupport.HttpResponse response = JenkinsProxySupport.send(
                URI.create(apiBase + path),
                method,
                headers,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                CONNECT_TIMEOUT,
                REQUEST_TIMEOUT);
        int code = response.statusCode();
        if (code == 401 || code == 403) {
            throw new IOException("API key or secret is incorrect (HTTP " + code + ")");
        }
        if (code >= 400) {
            throw new IOException("Webhook Relay API request failed: HTTP " + code
                    + " - " + response.body());
        }
        return response.body();
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
        @SerializedName("function_id")
        public String functionId;

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

    public static class Function {
        public String id;
        public String name;
        public String driver;
    }
}
