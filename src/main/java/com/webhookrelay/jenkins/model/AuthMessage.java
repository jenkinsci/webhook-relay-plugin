package com.webhookrelay.jenkins.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class AuthMessage {
    @SuppressFBWarnings(value = "SS_SHOULD_BE_STATIC", justification = "Gson serializes instance fields, not static ones")
    private final String action = "auth";
    private final String key;
    private final String secret;

    public AuthMessage(String key, String secret) {
        this.key = key;
        this.secret = secret;
    }

    public String getAction() {
        return action;
    }

    public String getKey() {
        return key;
    }

    public String getSecret() {
        return secret;
    }
}
