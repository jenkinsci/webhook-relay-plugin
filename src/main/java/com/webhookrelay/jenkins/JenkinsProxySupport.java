package com.webhookrelay.jenkins;

import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;
import org.java_websocket.client.WebSocketClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Applies the controller's proxy configuration to Webhook Relay traffic.
 *
 * <p>The JDK {@code HttpClient} cannot preserve an origin {@code Authorization}
 * header when a proxy authenticator is installed on Java 17 and 21. Webhook
 * Relay uses that header on every API request, so HTTP traffic uses Jenkins'
 * URLConnection helper instead. Before the first authenticated request, a
 * harmless probe installs Jenkins' authenticator and primes the JDK proxy
 * authentication cache used by both HTTP requests and WebSocket CONNECT.
 */
final class JenkinsProxySupport {

    private static final int PROBE_TIMEOUT_MILLIS = 5_000;
    private static final Object PROXY_AUTHENTICATION_LOCK = new Object();
    private static volatile ProxyConfiguration primedProxyConfiguration;

    private JenkinsProxySupport() {
    }

    static HttpResponse send(
            URI uri,
            String method,
            Map<String, String> headers,
            byte[] body,
            Duration connectTimeout,
            Duration readTimeout) throws IOException {

        HttpURLConnection connection = open(uri);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
            connection.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
            connection.setRequestMethod(method);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }

            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= HttpURLConnection.HTTP_BAD_REQUEST
                    ? connection.getErrorStream() : connection.getInputStream();
            return new HttpResponse(statusCode, readBody(responseStream));
        } finally {
            connection.disconnect();
        }
    }

    static void configure(WebSocketClient client, URI uri) throws IOException {
        ProxyConfiguration proxyConfiguration = Jenkins.get().getProxy();
        if (proxyConfiguration == null) {
            return;
        }

        Proxy proxy = proxyConfiguration.createProxy(uri.getHost());
        if (proxy != Proxy.NO_PROXY && proxyConfiguration.getUserName() != null) {
            primeProxyAuthentication(proxyConfiguration, uri);
        }
        client.setProxy(proxy);
    }

    @SuppressWarnings("deprecation")
    private static HttpURLConnection open(URI uri) throws IOException {
        ProxyConfiguration proxyConfiguration = Jenkins.get().getProxy();
        if (proxyConfiguration != null
                && proxyConfiguration.getUserName() != null
                && proxyConfiguration.createProxy(uri.getHost()) != Proxy.NO_PROXY) {
            primeProxyAuthentication(proxyConfiguration, uri);
        }
        URLConnection connection = ProxyConfiguration.open(uri.toURL());
        if (!(connection instanceof HttpURLConnection)) {
            throw new IOException("Unsupported HTTP connection for " + uri);
        }
        return (HttpURLConnection) connection;
    }

    /**
     * Sends a harmless HTTP HEAD through the configured proxy so the JDK caches
     * the challenged proxy credentials before a request body or WebSocket CONNECT
     * is attempted. Java-WebSocket ultimately uses the same JDK HTTP proxy stack,
     * so this avoids copying proxy credentials into WebSocket origin headers.
     */
    private static void primeProxyAuthentication(ProxyConfiguration proxyConfiguration, URI uri) throws IOException {
        if (primedProxyConfiguration == proxyConfiguration) {
            return;
        }
        synchronized (PROXY_AUTHENTICATION_LOCK) {
            if (primedProxyConfiguration == proxyConfiguration) {
                return;
            }
            executeProxyAuthenticationProbe(uri);
            primedProxyConfiguration = proxyConfiguration;
        }
    }

    @SuppressWarnings("deprecation")
    private static void executeProxyAuthenticationProbe(URI uri) throws IOException {
        URI probeUri;
        try {
            probeUri = new URI(
                    "http",
                    null,
                    uri.getHost(),
                    -1,
                    "/",
                    null,
                    null);
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid WebSocket URI " + uri, e);
        }

        URLConnection rawConnection = ProxyConfiguration.open(probeUri.toURL());
        if (!(rawConnection instanceof HttpURLConnection)) {
            throw new IOException("Unsupported proxy authentication probe for " + uri);
        }
        HttpURLConnection connection = (HttpURLConnection) rawConnection;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(PROBE_TIMEOUT_MILLIS);
            connection.setReadTimeout(PROBE_TIMEOUT_MILLIS);
            connection.setRequestMethod("HEAD");
            if (connection.getResponseCode() == HttpURLConnection.HTTP_PROXY_AUTH) {
                throw new IOException("Jenkins proxy authentication failed");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static final class HttpResponse {
        private final int statusCode;
        private final String body;

        HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        int statusCode() {
            return statusCode;
        }

        String body() {
            return body;
        }
    }
}
