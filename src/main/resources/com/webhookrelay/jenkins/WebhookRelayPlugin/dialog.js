// Resolves the Webhook Relay webhook URL for the configured bucket and shows it
// in a dialog with a copy button, instead of rendering inline form-validation markup.
(function () {
    function init() {
        var btn = document.getElementById("webhook-relay-resolve-btn");
        if (!btn || btn.dataset.bound === "true") {
            return;
        }
        btn.dataset.bound = "true";

        var dialog = document.getElementById("webhook-relay-url-dialog");
        var urlInput = document.getElementById("webhook-relay-url-value");
        var note = document.getElementById("webhook-relay-url-note");
        var bucketLink = document.getElementById("webhook-relay-url-bucket");
        var errorBox = document.getElementById("webhook-relay-resolve-error");
        var closeBtn = document.getElementById("webhook-relay-url-close");

        closeBtn.addEventListener("click", function () {
            dialog.close();
        });

        btn.addEventListener("click", function () {
            errorBox.style.display = "none";
            btn.disabled = true;

            var form = btn.closest("form");
            function fieldValue(name) {
                var el = form ? form.querySelector('[name="_.' + name + '"]') : null;
                return el ? el.value : "";
            }

            var params = new URLSearchParams();
            params.append("apiKey", fieldValue("apiKey"));
            params.append("apiSecret", fieldValue("apiSecret"));
            params.append("buckets", fieldValue("buckets"));
            params.append("scmPreset", fieldValue("scmPreset"));

            var headers = { "Content-Type": "application/x-www-form-urlencoded" };
            if (window.crumb && typeof window.crumb.wrap === "function") {
                headers = window.crumb.wrap(headers);
            }

            fetch(btn.dataset.resolveUrl, { method: "POST", headers: headers, body: params.toString() })
                .then(function (response) {
                    return response.json();
                })
                .then(function (data) {
                    btn.disabled = false;
                    if (data.ok) {
                        urlInput.textContent = data.url;
                        note.textContent =
                            "Webhooks received here are forwarded to " + data.endpointPath +
                            " on this Jenkins. Enable the connection and Save to start receiving them.";
                        if (data.bucketUrl) {
                            bucketLink.href = data.bucketUrl;
                            bucketLink.style.display = "";
                        } else {
                            bucketLink.style.display = "none";
                        }
                        dialog.showModal();
                    } else {
                        errorBox.textContent = data.error || "Failed to resolve webhook URL";
                        errorBox.style.display = "";
                    }
                })
                .catch(function (e) {
                    btn.disabled = false;
                    errorBox.textContent = "Failed to resolve webhook URL: " + e;
                    errorBox.style.display = "";
                });
        });
    }

    if (document.readyState !== "loading") {
        init();
    } else {
        document.addEventListener("DOMContentLoaded", init);
    }
})();
