package com.webhookrelay.jenkins;

import com.webhookrelay.jenkins.model.WebhookEvent;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WebhookForwarderTest {

    private final Gson gson = new Gson();

    @Test
    public void testDetermineDestinationWithInternalOutputUrl() {
        // A configured internal output has a full http(s) URL destination.
        String json = "{\"type\":\"webhook\",\"meta\":{\"output_destination\":\"http://localhost:8080/bitbucket-hook/\"},\"body\":\"{}\",\"method\":\"POST\"}";
        WebhookEvent event = gson.fromJson(json, WebhookEvent.class);

        WebhookForwarder forwarder = new WebhookForwarder("github-webhook/");
        // The explicit internal output destination wins over the SCM preset.
        assertEquals("bitbucket-hook/", forwarder.determineDestination(event));
    }

    @Test
    public void testDetermineDestinationFallsBackToScmPreset() {
        // With no internal output, output_destination holds the inbound request path
        // (e.g. "/") which must NOT be used as the Jenkins path. We route by preset.
        String json = "{\"type\":\"webhook\",\"meta\":{\"output_destination\":\"/\"},\"body\":\"{}\",\"method\":\"POST\"}";
        WebhookEvent event = gson.fromJson(json, WebhookEvent.class);

        WebhookForwarder forwarder = new WebhookForwarder("github-webhook/");
        assertEquals("github-webhook/", forwarder.determineDestination(event));
    }

    @Test
    public void testDetermineDestinationDefaultWhenNoPresetGiven() {
        String json = "{\"type\":\"webhook\",\"meta\":{},\"body\":\"{}\",\"method\":\"POST\"}";
        WebhookEvent event = gson.fromJson(json, WebhookEvent.class);

        WebhookForwarder forwarder = new WebhookForwarder();
        assertEquals("generic-webhook-trigger/invoke", forwarder.determineDestination(event));
    }

    @Test
    public void testWebhookEventDeserialization() {
        // Webhook Relay sends headers as a map of name -> list of values.
        String json = "{" +
                "\"type\":\"webhook\"," +
                "\"meta\":{" +
                    "\"id\":\"log-1\"," +
                    "\"bucked_id\":\"b1\"," +
                    "\"bucket_name\":\"my-bucket\"," +
                    "\"input_id\":\"i1\"," +
                    "\"input_name\":\"my-input\"," +
                    "\"output_name\":\"my-output\"," +
                    "\"output_destination\":\"/\"" +
                "}," +
                "\"headers\":{\"Content-Type\":[\"application/json\"],\"X-Github-Event\":[\"push\"]}," +
                "\"query\":\"foo=bar\"," +
                "\"body\":\"{\\\"ref\\\":\\\"refs/heads/main\\\"}\"," +
                "\"method\":\"POST\"" +
                "}";

        WebhookEvent event = gson.fromJson(json, WebhookEvent.class);

        assertEquals("webhook", event.getType());
        assertEquals("POST", event.getMethod());
        assertEquals("foo=bar", event.getQuery());
        assertNotNull(event.getBody());
        assertNotNull(event.getMeta());
        assertEquals("log-1", event.getMeta().getId());
        assertEquals("my-bucket", event.getMeta().getBucketName());
        assertEquals("b1", event.getMeta().getBucketId());
        assertEquals("/", event.getMeta().getOutputDestination());
        assertNotNull(event.getHeaders());
        List<String> contentType = event.getHeaders().get("Content-Type");
        assertNotNull(contentType);
        assertEquals("application/json", contentType.get(0));
        assertEquals("push", event.getHeaders().get("X-Github-Event").get(0));
    }
}
