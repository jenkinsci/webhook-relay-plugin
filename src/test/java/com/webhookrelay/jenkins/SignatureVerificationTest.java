package com.webhookrelay.jenkins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureVerificationTest {

    /**
     * The fixtures under src/test/resources/.../signature/ are the exact sources
     * that were run end to end against production Webhook Relay. What the plugin
     * ships must stay byte-identical to them.
     */
    @Test
    void generatedSourceIsByteIdenticalToTestedFixtures() throws IOException {
        for (String preset : new String[] {"github", "gitlab", "bitbucket", "gitea"}) {
            String resource = "signature/" + preset + ".js";
            try (InputStream in = SignatureVerificationTest.class.getResourceAsStream(resource)) {
                assertNotNull(in, "missing fixture " + resource);
                assertEquals(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                        SignatureVerification.functionSource(preset), preset);
            }
        }
    }

    @Test
    void presetsCheckTheProviderHeader() {
        assertTrue(SignatureVerification.functionSource("github").contains(
                "const expected = \"sha256=\" + crypto.hmac(\"sha256\", secret, r.body)"));
        assertTrue(SignatureVerification.functionSource("bitbucket").contains(
                "r.headers[\"X-Hub-Signature\"]"));
        assertTrue(SignatureVerification.functionSource("gitea").contains(
                "const expected = crypto.hmac(\"sha256\", secret, r.body)"));
        String gitlab = SignatureVerification.functionSource("gitlab");
        assertTrue(gitlab.contains("const expected = secret"));
        assertFalse(gitlab.contains("r.body"));
    }

    @Test
    void rejectionsFailClosedAndNeverEchoTheExpectedValue() {
        for (String preset : new String[] {"github", "gitlab", "bitbucket", "gitea"}) {
            String js = SignatureVerification.functionSource(preset);
            assertTrue(js.contains("if (!secret || !received ||"), preset);
            assertTrue(js.contains("r.setResponseStatus(401)"), preset);
            assertTrue(js.contains("r.stopForwarding()"), preset);
            assertTrue(js.contains("r.setResponseBody(\"invalid webhook signature\")"), preset);
            // The only response body is the fixed string above.
            assertEquals(1, js.split("setResponseBody", -1).length - 1, preset);
        }
    }

    @Test
    void genericAndCustomPresetsAreUnsupported() {
        assertNull(SignatureVerification.scheme("generic"));
        assertNull(SignatureVerification.scheme("custom"));
        assertThrows(IllegalArgumentException.class, () -> SignatureVerification.functionSource("generic"));
    }

    @Test
    void functionNamesAreStableManagedAndWithinTheApiLimit() {
        assertEquals("jenkins-github-signature-check-ci",
                SignatureVerification.functionName("github", "ci"));
        String longName = SignatureVerification.functionName("bitbucket",
                "a-very-long-bucket-name-that-goes-on-and-on");
        assertEquals(50, longName.length());
        assertTrue(SignatureVerification.isManagedFunctionName(longName));
        assertTrue(SignatureVerification.isManagedFunctionName("jenkins-gitlab-signature-check-ci"));
        assertFalse(SignatureVerification.isManagedFunctionName("my-own-function"));
        assertFalse(SignatureVerification.isManagedFunctionName(null));
    }

    @Test
    void functionRequestBodyBase64EncodesTheSource() {
        JsonObject body = JsonParser.parseString(
                WebhookRelayAPI.functionRequestBody("fn", "js", "console.log('hi')")).getAsJsonObject();
        assertEquals("fn", body.get("name").getAsString());
        assertEquals("js", body.get("driver").getAsString());
        assertEquals("console.log('hi')", new String(
                Base64.getDecoder().decode(body.get("payload").getAsString()), StandardCharsets.UTF_8));
    }

    @Test
    @WithJenkins
    void enableCreatesFunctionSetsSecretAndAttachesToInput(JenkinsRule jenkins) throws Exception {
        try (FakeRelay relay = new FakeRelay("")) {
            WebhookRelayAPI api = relay.api();
            String summary = SignatureVerification.enable(
                    api, api.getBucket("b1"), "github", Secret.fromString("s3cret"));

            assertTrue(summary.contains("X-Hub-Signature-256"));
            JsonObject created = relay.body("POST /v1/functions");
            assertEquals("jenkins-github-signature-check-jenkins", created.get("name").getAsString());
            assertEquals("js", created.get("driver").getAsString());

            JsonObject config = relay.body("PUT /v1/functions/f-new/config");
            assertEquals("WEBHOOK_SECRET", config.get("key").getAsString());
            assertEquals("s3cret", config.get("value").getAsString());

            JsonObject input = relay.body("PUT /v1/buckets/b1/inputs/i1");
            assertEquals("f-new", input.get("function_id").getAsString());
            // The input is sent back whole so the update does not wipe other settings.
            assertEquals("jenkins.hooks.webhookrelay.com", input.get("custom_domain").getAsString());
            assertEquals("ok", input.get("body").getAsString());
        }
    }

    @Test
    @WithJenkins
    void enableUpdatesExistingManagedFunctionInPlace(JenkinsRule jenkins) throws Exception {
        try (FakeRelay relay = new FakeRelay("f-old")) {
            relay.functions = "[{\"id\":\"f-old\",\"name\":\"jenkins-github-signature-check-jenkins\"}]";
            WebhookRelayAPI api = relay.api();
            SignatureVerification.enable(api, api.getBucket("b1"), "github", Secret.fromString("s3cret"));

            assertTrue(relay.requests.containsKey("PUT /v1/functions/f-old"));
            assertFalse(relay.requests.containsKey("POST /v1/functions"));
            // Already attached: no input update needed.
            assertFalse(relay.requests.containsKey("PUT /v1/buckets/b1/inputs/i1"));
        }
    }

    @Test
    @WithJenkins
    void enableRefusesToReplaceAUserFunction(JenkinsRule jenkins) throws Exception {
        try (FakeRelay relay = new FakeRelay("f-user")) {
            relay.functions = "[{\"id\":\"f-user\",\"name\":\"my-transform\"}]";
            WebhookRelayAPI api = relay.api();
            IOException e = assertThrows(IOException.class, () -> SignatureVerification.enable(
                    api, api.getBucket("b1"), "github", Secret.fromString("s3cret")));
            assertTrue(e.getMessage().contains("already has a function attached"));
            assertTrue(relay.requests.keySet().stream().noneMatch(k -> k.startsWith("PUT ")
                    || k.startsWith("POST ")));
        }
    }

    @Test
    @WithJenkins
    void disableDetachesOnlyManagedFunctions(JenkinsRule jenkins) throws Exception {
        try (FakeRelay relay = new FakeRelay("f-old")) {
            relay.functions = "[{\"id\":\"f-old\",\"name\":\"jenkins-gitlab-signature-check-jenkins\"}]";
            WebhookRelayAPI api = relay.api();
            assertEquals(Collections.singletonList("default"),
                    SignatureVerification.disable(api, api.getBucket("b1")));
            assertEquals("", relay.body("PUT /v1/buckets/b1/inputs/i1").get("function_id").getAsString());
        }
        try (FakeRelay relay = new FakeRelay("f-user")) {
            relay.functions = "[{\"id\":\"f-user\",\"name\":\"my-transform\"}]";
            WebhookRelayAPI api = relay.api();
            assertTrue(SignatureVerification.disable(api, api.getBucket("b1")).isEmpty());
            assertFalse(relay.requests.containsKey("PUT /v1/buckets/b1/inputs/i1"));
        }
    }

    @Test
    @WithJenkins
    void pluginSyncAppliesOnceThenDetachesWhenSecretCleared(JenkinsRule jenkins) throws Exception {
        WebhookRelayPlugin plugin = WebhookRelayPlugin.get();
        plugin.setBuckets("jenkins");
        plugin.setScmPreset("gitea");
        plugin.setWebhookSecret(Secret.fromString("s3cret"));

        try (FakeRelay relay = new FakeRelay("")) {
            plugin.syncSignatureVerification(relay.api());
            assertFalse(plugin.isVerificationFailed(), plugin.getVerificationStatus());
            assertTrue(plugin.getVerificationStatus().contains("X-Gitea-Signature"));
            assertEquals("f-new", relay.body("PUT /v1/buckets/b1/inputs/i1").get("function_id").getAsString());
        }

        // Unchanged configuration (e.g. a restart): no API calls at all.
        try (FakeRelay relay = new FakeRelay("f-new")) {
            plugin.syncSignatureVerification(relay.api());
            assertTrue(relay.requests.isEmpty(), relay.requests.keySet().toString());
        }

        // Secret cleared: the managed function is detached.
        plugin.setWebhookSecret(Secret.fromString(""));
        try (FakeRelay relay = new FakeRelay("f-new")) {
            relay.functions = "[{\"id\":\"f-new\",\"name\":\"jenkins-gitea-signature-check-jenkins\"}]";
            plugin.syncSignatureVerification(relay.api());
            assertFalse(plugin.isVerificationFailed(), plugin.getVerificationStatus());
            assertEquals("", relay.body("PUT /v1/buckets/b1/inputs/i1").get("function_id").getAsString());
            assertEquals("", plugin.desiredVerification());
        }
    }

    @Test
    @WithJenkins
    void pluginSyncReportsUnsupportedPreset(JenkinsRule jenkins) throws Exception {
        WebhookRelayPlugin plugin = WebhookRelayPlugin.get();
        plugin.setBuckets("jenkins");
        plugin.setScmPreset("generic");
        plugin.setWebhookSecret(Secret.fromString("s3cret"));
        try (FakeRelay relay = new FakeRelay("")) {
            plugin.syncSignatureVerification(relay.api());
            assertTrue(plugin.isVerificationFailed());
            assertTrue(plugin.getVerificationStatus().contains("not available for the 'generic' preset"));
            assertTrue(relay.requests.isEmpty());
        }
    }

    @Test
    @WithJenkins
    void pluginSyncSurfacesFunctionCreateFailure(JenkinsRule jenkins) throws Exception {
        WebhookRelayPlugin plugin = WebhookRelayPlugin.get();
        plugin.setBuckets("jenkins");
        plugin.setScmPreset("github");
        plugin.setWebhookSecret(Secret.fromString("s3cret"));
        try (FakeRelay relay = new FakeRelay("")) {
            relay.createFunctionError = "you have reached the maximum amount of functions for your account (100), "
                    + "delete some existing ones before creating new";
            plugin.syncSignatureVerification(relay.api());
            assertTrue(plugin.isVerificationFailed());
            assertTrue(plugin.getVerificationStatus().startsWith("Signature verification sync failed:"),
                    plugin.getVerificationStatus());
            assertTrue(plugin.getVerificationStatus().contains("HTTP 400"), plugin.getVerificationStatus());
            assertTrue(plugin.getVerificationStatus().contains("maximum amount of functions for your account (100)"),
                    plugin.getVerificationStatus());
            // Nothing was attached, and the failed state is retried on the next start.
            assertFalse(relay.requests.containsKey("PUT /v1/buckets/b1/inputs/i1"));
            assertEquals("github|jenkins", plugin.desiredVerification());
        }
        try (FakeRelay relay = new FakeRelay("")) {
            plugin.syncSignatureVerification(relay.api());
            assertFalse(plugin.isVerificationFailed(), plugin.getVerificationStatus());
            assertEquals("f-new", relay.body("PUT /v1/buckets/b1/inputs/i1").get("function_id").getAsString());
        }
    }

    /** Minimal stand-in for the Webhook Relay REST API that records request bodies. */
    private static final class FakeRelay implements AutoCloseable {
        final HttpServer server;
        final Map<String, String> requests = new ConcurrentHashMap<>();
        volatile String functions = "[]";
        /** When set, POST /v1/functions fails with HTTP 400 and this body. */
        volatile String createFunctionError;

        FakeRelay(String attachedFunctionId) throws IOException {
            String bucket = "{\"id\":\"b1\",\"name\":\"jenkins\",\"inputs\":[{\"id\":\"i1\",\"name\":\"default\","
                    + "\"bucket_id\":\"b1\",\"function_id\":\"" + attachedFunctionId + "\","
                    + "\"custom_domain\":\"jenkins.hooks.webhookrelay.com\",\"body\":\"ok\"}]}";
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/", exchange -> {
                String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
                requests.put(key, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if ("POST /v1/functions".equals(key) && createFunctionError != null) {
                    byte[] error = createFunctionError.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, error.length);
                    exchange.getResponseBody().write(error);
                    exchange.close();
                    return;
                }
                String response;
                switch (key) {
                    case "GET /v1/functions":
                        response = functions;
                        break;
                    case "POST /v1/functions":
                        response = "{\"id\":\"f-new\",\"name\":\"created\"}";
                        break;
                    case "GET /v1/buckets":
                        response = "[" + bucket + "]";
                        break;
                    case "GET /v1/buckets/b1":
                        response = bucket;
                        break;
                    default:
                        response = "{}";
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        WebhookRelayAPI api() {
            return new WebhookRelayAPI(Secret.fromString("k"), Secret.fromString("s"),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        }

        JsonObject body(String key) {
            String body = requests.get(key);
            if (body == null) {
                throw new AssertionError("no request " + key + "; got " + new ArrayList<>(requests.keySet()));
            }
            return JsonParser.parseString(body).getAsJsonObject();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
