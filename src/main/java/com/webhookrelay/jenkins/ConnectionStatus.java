package com.webhookrelay.jenkins;

public enum ConnectionStatus {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting..."),
    CONNECTED("Connected"),
    AUTHENTICATED("Authenticated"),
    SUBSCRIBED("Subscribed"),
    AUTH_FAILED("Authentication Failed"),
    ERROR("Error");

    private final String displayName;

    ConnectionStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
