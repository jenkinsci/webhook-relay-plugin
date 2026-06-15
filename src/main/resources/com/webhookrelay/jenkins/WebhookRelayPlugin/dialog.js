// Resolves the Webhook Relay webhook URL for the configured bucket and shows it in a
// Jenkins dialog with a copy button, instead of rendering inline form-validation markup.
(function () {
    function init() {
        var btn = document.getElementById("webhook-relay-resolve-btn");
        if (!btn || btn.dataset.bound === "true") {
            return;
        }
        btn.dataset.bound = "true";
        var errorBox = document.getElementById("webhook-relay-resolve-error");

        btn.addEventListener("click", function () {
            errorBox.classList.add("jenkins-hidden");
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

            var headers = crumb.wrap({ "Content-Type": "application/x-www-form-urlencoded" });
            fetch(btn.dataset.resolveUrl, { method: "POST", headers: headers, body: params.toString() })
                .then(function (response) {
                    return response.json();
                })
                .then(function (data) {
                    btn.disabled = false;
                    if (data.ok) {
                        var template = document.getElementById("webhook-relay-url-dialog");
                        var title = template.dataset.title;
                        var content = template.content.firstElementChild.cloneNode(true);

                        content.querySelector("#webhook-relay-url-value").textContent = data.url;
                        content.querySelector("#webhook-relay-url-note").textContent =
                            "Webhooks received here are forwarded to " + data.endpointPath +
                            " on this Jenkins. Enable the connection and Save to start receiving them.";

                        var bucketLink = content.querySelector("#webhook-relay-url-bucket");
                        if (data.bucketUrl) {
                            bucketLink.href = data.bucketUrl;
                            bucketLink.classList.remove("jenkins-hidden");
                        } else {
                            bucketLink.classList.add("jenkins-hidden");
                        }

                        Behaviour.applySubtree(content, false);
                        dialog.alert(title, {
                            content: content,
                            okText: "Done",
                            maxWidth: "600px",
                        });
                    } else {
                        errorBox.textContent = data.error || "Failed to resolve webhook URL";
                        errorBox.classList.remove("jenkins-hidden");
                    }
                })
                .catch(function (e) {
                    btn.disabled = false;
                    errorBox.textContent = "Failed to resolve webhook URL: " + e;
                    errorBox.classList.remove("jenkins-hidden");
                });
        });
    }

    if (document.readyState !== "loading") {
        init();
    } else {
        document.addEventListener("DOMContentLoaded", init);
    }
})();
