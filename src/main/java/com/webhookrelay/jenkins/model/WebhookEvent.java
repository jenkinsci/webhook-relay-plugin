package com.webhookrelay.jenkins.model;

import com.google.gson.annotations.SerializedName;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Map;

@SuppressFBWarnings(value = "UWF_UNWRITTEN_FIELD", justification = "Fields are populated by Gson deserialization")
public class WebhookEvent {
    private String type;
    private String status;
    private Meta meta;
    private Map<String, String> headers;
    private String query;
    private String body;
    private String method;

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public Meta getMeta() {
        return meta;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getQuery() {
        return query;
    }

    public String getBody() {
        return body;
    }

    public String getMethod() {
        return method;
    }

    public static class Meta {
        private String id;

        @SerializedName("bucked_id")
        private String bucketId;

        @SerializedName("bucket_name")
        private String bucketName;

        @SerializedName("input_id")
        private String inputId;

        @SerializedName("input_name")
        private String inputName;

        @SerializedName("output_name")
        private String outputName;

        @SerializedName("output_destination")
        private String outputDestination;

        public String getId() {
            return id;
        }

        public String getBucketId() {
            return bucketId;
        }

        public String getBucketName() {
            return bucketName;
        }

        public String getInputId() {
            return inputId;
        }

        public String getInputName() {
            return inputName;
        }

        public String getOutputName() {
            return outputName;
        }

        public String getOutputDestination() {
            return outputDestination;
        }
    }
}
