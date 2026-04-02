package com.webhookrelay.jenkins;

import hudson.util.Secret;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

public class WebhookRelayPluginTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void testConfigurationRoundTrip() throws Exception {
        WebhookRelayPlugin plugin = WebhookRelayPlugin.get();
        assertNotNull("Plugin should be loaded", plugin);

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
    public void testDefaultValues() {
        WebhookRelayPlugin plugin = WebhookRelayPlugin.get();
        assertNotNull(plugin);

        assertTrue("Should be enabled by default", plugin.isEnabled());
        assertEquals(ConnectionStatus.DISCONNECTED, plugin.getConnectionStatus());
        assertEquals("", plugin.getStatusMessage());
    }

    @Test
    public void testConnectionStatusUpdate() {
        WebhookRelayPlugin plugin = WebhookRelayPlugin.get();
        assertNotNull(plugin);

        plugin.updateConnectionStatus(ConnectionStatus.CONNECTED, "test message");
        assertEquals(ConnectionStatus.CONNECTED, plugin.getConnectionStatus());
        assertEquals("test message", plugin.getStatusMessage());
    }
}
