# Webhook Relay plugin — Docker demo

A self-contained Jenkins that demonstrates the GitHub → Webhook Relay bucket → plugin →
build flow, with no public Jenkins required.

## What's included

- **`Dockerfile`** — `jenkins/jenkins:lts-jdk17` with the plugin's dependencies pre-installed
  (`git`, `github`, `workflow-aggregator`, `generic-webhook-trigger`, `configuration-as-code`).
- **`casc.yaml`** — Configuration as Code: an `admin` user, the internal Jenkins URL, and an
  optional GitHub credential for cloning a private demo repository.
- **`docker-compose.yml`** — runs Jenkins on <http://localhost:8095>.
- **`jobs/webhook-relay-demo.config.xml`** — a Pipeline job triggered by a GitHub push.
- **`Jenkinsfile`** — the pipeline the demo job runs (`scriptPath: demo/Jenkinsfile`).

## Run it

```bash
# from the repository root
mvn -q clean package                                   # build target/webhook-relay.hpi

# optional: lets the demo job clone a private repo
export GITHUB_USER=<your-github-user>
export GITHUB_TOKEN=$(gh auth token)

docker compose -f demo/docker-compose.yml up --build
```

Open <http://localhost:8095> (admin / admin).

## Steps

1. **Install the plugin** — *Manage Jenkins → Plugins → Advanced settings → Deploy Plugin*,
   upload `../target/webhook-relay.hpi`, restart.
2. **Configure** — *Manage Jenkins → System → Webhook Relay*: enter your API key/secret, a
   bucket name, pick the GitHub preset, **Enable**, **Save**. The status should read
   **✔ Subscribed**.
3. **Get the webhook URL** — click **Get Webhook URL** and paste the value into your GitHub
   repository's *Settings → Webhooks*.
4. **Create the demo job** (or import `jobs/webhook-relay-demo.config.xml`):

   ```bash
   JENKINS=http://localhost:8095
   CRUMB=$(curl -s -c /tmp/jc "$JENKINS/crumbIssuer/api/json" | python3 -c 'import sys,json;print(json.load(sys.stdin)["crumb"])')
   curl -s -b /tmp/jc -H "Jenkins-Crumb: $CRUMB" -H 'Content-Type: application/xml' \
     --data-binary @demo/jobs/webhook-relay-demo.config.xml \
     "$JENKINS/createItem?name=webhook-relay-demo"
   ```

5. **Push to the repo** — GitHub sends a push event to the bucket, the plugin forwards it to
   `/github-webhook/`, and the `webhook-relay-demo` build starts. The build page shows
   *"Started by GitHub push"*.

## Notes

- The Jenkins URL is set to `http://localhost:8080/` (the container-internal port) so the
  plugin can deliver webhooks back to Jenkins itself. Browse via the mapped host port `8095`.
- The demo disables nothing security-wise by default; for unattended screenshotting you may
  prefer to relax authentication, but that is **not** recommended outside a throwaway demo.
