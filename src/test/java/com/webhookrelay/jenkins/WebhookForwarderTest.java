package com.webhookrelay.jenkins;

import com.webhookrelay.jenkins.model.WebhookEvent;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.*;

public class WebhookForwarderTest {

    private final Gson gson = new Gson();

    @Test
    public void testDetermineDestinationWithOutputDestination() {
        String json = "{\"type\":\"webhook\",\"meta\":{\"output_destination\":\"http://localhost:8080/github-webhook/\"},\"body\":\"{}\",\"method\":\"POST\"}";
        WebhookEvent event = gson.fromJson(json, WebhookEvent.class);

        WebhookForwarder forwarder = new WebhookForwarder();
        String dest = forwarder.determineDestination(event);
        assertEquals("github-webhook/", dest);
    }

    @Test
    public void testDetermineDestinationWithPath() {
        String json = "{\"type\":\"webhook\",\"meta\":{\"output_destination\":\"/generic-webhook-trigger/invoke\"},\"body\":\"{}\",\"method\":\"POST\"}";
        WebhookEvent event = gson.fromJson(json, WebhookEvent.class);

        WebhookForwarder forwarder = new WebhookForwarder();
        String dest = forwarder.determineDestination(event);
        assertEquals("generic-webhook-trigger/invoke", dest);
    }

    @Test
    public void testDetermineDestinationFallback() {
        String json = "{\"type\":\"webhook\",\"meta\":{},\"body\":\"{}\",\"method\":\"POST\"}";
        WebhookEvent event = gson.fromJson(json, WebhookEvent.class);

        WebhookForwarder forwarder = new WebhookForwarder();
        String dest = forwarder.determineDestination(event);
        assertEquals("generic-webhook-trigger/invoke", dest);
    }

    @Test
    public void testWebhookEventDeserialization() {
        String json = "{" +
                "\"type\":\"webhook\"," +
                "\"meta\":{" +
                    "\"bucked_id\":\"b1\"," +
                    "\"bucket_name\":\"my-bucket\"," +
                    "\"input_id\":\"i1\"," +
                    "\"input_name\":\"my-input\"," +
                    "\"output_name\":\"my-output\"," +
                    "\"output_destination\":\"http://localhost:8080/github-webhook/\"" +
                "}," +
                "\"headers\":{\"Content-Type\":\"application/json\"}," +
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
        assertEquals("my-bucket", event.getMeta().getBucketName());
        assertEquals("b1", event.getMeta().getBucketId());
        assertEquals("http://localhost:8080/github-webhook/", event.getMeta().getOutputDestination());
        assertNotNull(event.getHeaders());
        assertEquals("application/json", event.getHeaders().get("Content-Type"));
    }
}
