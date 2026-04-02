package com.webhookrelay.jenkins.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.List;

public class SubscribeMessage {
    @SuppressFBWarnings(value = "SS_SHOULD_BE_STATIC", justification = "Gson serializes instance fields, not static ones")
    private final String action = "subscribe";
    private final List<String> buckets;

    public SubscribeMessage(List<String> buckets) {
        this.buckets = buckets;
    }

    public String getAction() {
        return action;
    }

    public List<String> getBuckets() {
        return buckets;
    }
}
