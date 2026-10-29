package com.webhookrelay.jenkins;

import hudson.util.Secret;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configures webhook signature verification on the Webhook Relay side.
 *
 * <p>For a supported SCM preset this maintains one Lua Function per bucket that
 * checks the provider's signature (or token) header against the
 * {@code WEBHOOK_SECRET} function config variable, and attaches it to every
 * input of the bucket. Requests that fail the check get a 401 from Webhook
 * Relay and are never forwarded to Jenkins.
 */
final class SignatureVerification {

    static final String DRIVER = "lua";
    static final String SECRET_KEY = "WEBHOOK_SECRET";

    private static final String NAME_PREFIX = "jenkins-";
    private static final String NAME_MARKER = "-signature-check";
    /** Webhook Relay rejects function names longer than this. */
    private static final int MAX_FUNCTION_NAME = 50;

    /** How a provider proves a request is authentic. */
    enum Kind {
        /** Header carries {@code prefix + hex(hmac-sha256(body, secret))}. */
        HMAC_SHA256,
        /** Header carries the shared secret itself. */
        TOKEN
    }

    static final class Scheme {
        final String header;
        final String prefix;
        final Kind kind;

        Scheme(String header, String prefix, Kind kind) {
            this.header = header;
            this.prefix = prefix;
            this.kind = kind;
        }
    }

    private static final Map<String, Scheme> SCHEMES = new HashMap<>();
    static {
        SCHEMES.put("github", new Scheme("X-Hub-Signature-256", "sha256=", Kind.HMAC_SHA256));
        SCHEMES.put("gitea", new Scheme("X-Gitea-Signature", "", Kind.HMAC_SHA256));
        SCHEMES.put("bitbucket", new Scheme("X-Hub-Signature", "sha256=", Kind.HMAC_SHA256));
        SCHEMES.put("gitlab", new Scheme("X-Gitlab-Token", "", Kind.TOKEN));
    }

    private SignatureVerification() {
    }

    /** The verification scheme for a preset, or {@code null} if it has none. */
    static Scheme scheme(String preset) {
        return preset == null ? null : SCHEMES.get(preset);
    }

    static String unsupportedMessage(String preset) {
        return "Webhook signature verification is not available for the '" + preset
                + "' preset (supported: GitHub, GitLab, Bitbucket, Gitea). "
                + "Clear the webhook secret or pick a supported preset.";
    }

    /**
     * Stable function name for a bucket and preset, truncated to the API's
     * 50-character limit.
     */
    static String functionName(String preset, String bucketName) {
        String name = NAME_PREFIX + preset + NAME_MARKER + "-" + bucketName;
        return name.length() > MAX_FUNCTION_NAME ? name.substring(0, MAX_FUNCTION_NAME) : name;
    }

    /** Whether a function was created by this plugin (for any preset). */
    static boolean isManagedFunctionName(String name) {
        return name != null && name.startsWith(NAME_PREFIX) && name.contains(NAME_MARKER);
    }

    /**
     * Lua source for the preset's check. Rejections never echo the expected
     * signature, and both sides are HMAC'd before comparing so the comparison
     * time does not reveal how much of a guessed signature was correct.
     */
    static String luaSource(String preset) {
        Scheme scheme = scheme(preset);
        if (scheme == null) {
            throw new IllegalArgumentException(unsupportedMessage(preset));
        }
        StringBuilder lua = new StringBuilder();
        lua.append("-- Managed by the Jenkins Webhook Relay plugin (preset: ").append(preset).append(").\n");
        lua.append("-- Changes are overwritten when the Jenkins configuration is saved.\n");
        lua.append("local crypto = require('crypto')\n");
        lua.append("\n");
        lua.append("local function reject()\n");
        lua.append("  r:SetResponseStatusCode(401)\n");
        lua.append("  r:SetResponseBody('invalid webhook signature')\n");
        lua.append("  r:StopForwarding()\n");
        lua.append("end\n");
        lua.append("\n");
        lua.append("local secret = cfg:GetValue('").append(SECRET_KEY).append("')\n");
        lua.append("local provided = r.RequestHeader['").append(scheme.header).append("'] or ''\n");
        lua.append("\n");
        lua.append("if secret == '' or provided == '' then\n");
        lua.append("  reject()\n");
        lua.append("  return\n");
        lua.append("end\n");
        lua.append("\n");
        if (scheme.kind == Kind.HMAC_SHA256) {
            lua.append("local digest, err = crypto.hmac('sha256', r.RequestBody, secret)\n");
            lua.append("if err then error(err) end\n");
            lua.append("local expected = '").append(scheme.prefix).append("' .. digest\n");
            lua.append("provided = string.lower(provided)\n");
        } else {
            lua.append("local expected = secret\n");
        }
        lua.append("\n");
        lua.append("if crypto.hmac('sha256', expected, secret) ~= crypto.hmac('sha256', provided, secret) then\n");
        lua.append("  reject()\n");
        lua.append("end\n");
        return lua.toString();
    }

    /**
     * Creates or updates the bucket's verification function, stores the secret
     * in its config and attaches it to every input of the bucket.
     *
     * @return a short human-readable summary
     * @throws IOException if any API call fails, or an input already has a
     *     function attached that this plugin does not manage
     */
    static String enable(WebhookRelayAPI api, WebhookRelayAPI.Bucket bucket, String preset, Secret secret)
            throws IOException {
        Scheme scheme = scheme(preset);
        if (scheme == null) {
            throw new IOException(unsupportedMessage(preset));
        }
        if (bucket.inputs == null || bucket.inputs.isEmpty()) {
            throw new IOException("Bucket '" + bucket.name + "' has no inputs to protect");
        }

        List<WebhookRelayAPI.Function> functions = api.listFunctions();
        Map<String, String> namesById = new HashMap<>();
        for (WebhookRelayAPI.Function f : functions) {
            namesById.put(f.id, f.name);
        }

        // Refuse to replace a function the user attached themselves.
        for (WebhookRelayAPI.Input input : bucket.inputs) {
            if (input.functionId != null && !input.functionId.isEmpty()
                    && !isManagedFunctionName(namesById.get(input.functionId))) {
                throw new IOException("Input '" + input.name + "' of bucket '" + bucket.name
                        + "' already has a function attached. Detach it in Webhook Relay "
                        + "(or add the signature check to that function yourself).");
            }
        }

        String name = functionName(preset, bucket.name);
        String source = luaSource(preset);
        String functionId = null;
        for (WebhookRelayAPI.Function f : functions) {
            if (name.equals(f.name)) {
                functionId = f.id;
                break;
            }
        }
        if (functionId == null) {
            functionId = api.createFunction(name, DRIVER, source).id;
        } else {
            api.updateFunction(functionId, name, DRIVER, source);
        }
        api.setFunctionConfig(functionId, SECRET_KEY, Secret.toString(secret));

        for (WebhookRelayAPI.Input input : bucket.inputs) {
            if (!functionId.equals(input.functionId)) {
                api.setInputFunction(bucket.id, input.id, functionId);
            }
        }
        return "Verifying " + scheme.header + " on bucket '" + bucket.name + "' (function " + name + ")";
    }

    /**
     * Detaches plugin-managed verification functions from the bucket's inputs.
     * Functions attached by the user are left alone; the managed functions
     * themselves are kept so re-enabling is a single update.
     *
     * @return the names of inputs that were detached
     */
    static List<String> disable(WebhookRelayAPI api, WebhookRelayAPI.Bucket bucket) throws IOException {
        List<String> detached = new ArrayList<>();
        if (bucket.inputs == null) {
            return detached;
        }
        Map<String, String> namesById = null;
        for (WebhookRelayAPI.Input input : bucket.inputs) {
            if (input.functionId == null || input.functionId.isEmpty()) {
                continue;
            }
            if (namesById == null) {
                namesById = new HashMap<>();
                for (WebhookRelayAPI.Function f : api.listFunctions()) {
                    namesById.put(f.id, f.name);
                }
            }
            if (isManagedFunctionName(namesById.get(input.functionId))) {
                api.setInputFunction(bucket.id, input.id, "");
                detached.add(input.name);
            }
        }
        return detached;
    }
}
