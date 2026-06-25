package com.webhookrelay.jenkins;

import hudson.util.Secret;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class WebhookRelayPluginTest {

    @Test
    void testConfigurationRoundTrip(JenkinsRule jenkins) throws Exception {
        WebhookRelayPlugin plugin = WebhookRelayPlugin.get();
        assertNotNull(plugin, "Plugin should be loaded");

        plugin.setApiKey(Secret.fromString("test-key"));
        plugin.setApiSecret(Secret.fromString("test-secret"));
        plugin.setBuckets("bucket1, bucket2");
        plugin.setEnabled(false);
        plugin.save();

        // Reload
        plugin.load();
        assertEquals("test-key", Secret.toString(plugin.getApiKey()));
        assertEquals("test-secret", Secret.toString(plugin.getApiSecret()));
        assertEquals("bucket1, bucket2", plugin.getBuckets());
        assertFalse(plugin.isEnabled());
    }

    @Test
    void testDefaultValues(JenkinsRule jenkins) {
        WebhookRelayPlugin plugin = WebhookRelayPlugin.get();
        assertNotNull(plugin);

        assertTrue(plugin.isEnabled(), "Should be enabled by default");
        assertEquals(ConnectionStatus.DISCONNECTED, plugin.getConnectionStatus());
        assertEquals("", plugin.getStatusMessage());
    }

    @Test
    void testScmPresetEndpointPaths(JenkinsRule jenkins) {
        WebhookRelayPlugin plugin = WebhookRelayPlugin.get();
        assertNotNull(plugin);

        plugin.setScmPreset("gitea");
        assertEquals("gitea-webhook/post", plugin.getWebhookEndpointPath());

        plugin.setScmPreset("github");
        assertEquals("github-webhook/", plugin.getWebhookEndpointPath());

        // Unknown presets fall back to the GitHub endpoint.
        plugin.setScmPreset("does-not-exist");
        assertEquals("github-webhook/", plugin.getWebhookEndpointPath());
    }

    @Test
    void testConnectionStatusUpdate(JenkinsRule jenkins) {
        WebhookRelayPlugin plugin = WebhookRelayPlugin.get();
        assertNotNull(plugin);

        plugin.updateConnectionStatus(ConnectionStatus.CONNECTED, "test message");
        assertEquals(ConnectionStatus.CONNECTED, plugin.getConnectionStatus());
        assertEquals("test message", plugin.getStatusMessage());
    }
}
