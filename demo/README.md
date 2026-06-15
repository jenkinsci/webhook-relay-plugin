# Webhook Relay plugin — Docker demo

A self-contained Jenkins that demonstrates the GitHub → Webhook Relay bucket → plugin →
build flow, with no public Jenkins required.

## What's included

- **`Dockerfile`** — `jenkins/jenkins:lts-jdk17` with the plugin's dependencies pre-installed
  (`git`, `github`, `workflow-aggregator`, `generic-webhook-trigger`, `configuration-as-code`,
  `gson-api`, `ionicons-api`). The Webhook Relay plugin itself is **not** baked in, so you can
  practise installing it.
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

1. **Install the plugin.** Open **Manage Jenkins → Plugins → Advanced settings**, scroll to
   **Deploy Plugin**, choose `target/webhook-relay.hpi` (built in the step above) and click
   **Deploy**. You should see *“webhook-relay — Success”*; the plugin loads immediately (no
   restart required because its dependencies are already installed). A restart is still fine
   if you prefer a clean slate.
2. **Configure.** Open **Manage Jenkins → System** and find the **Webhook Relay** section.
   Tick **Connect to Webhook Relay and forward webhooks to Jenkins**, enter your API
   key/secret ([tokens](https://my.webhookrelay.com/tokens)), a **Bucket name**
   (e.g. `jenkins-plugin`), pick the **GitHub** preset, then **Save**. Use **Test Connection**
   to check the credentials; once saved, **Connection Status** shows a green **Subscribed**
   banner.
3. **Get the webhook URL.** Click **Get Webhook URL** — a dialog shows the public URL with a
   copy button. Paste it into your GitHub repository's *Settings → Webhooks → Add webhook*
   (content type `application/json`).
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

- Sign in with **admin / admin** (provisioned by `casc.yaml`).
- The Jenkins URL is set to `http://localhost:8080/` (the container-internal port) so the
  plugin can deliver webhooks back to Jenkins itself. Browse via the mapped host port `8095`.
- To start over from a clean, plugin-free state:
  `docker compose -f demo/docker-compose.yml down -v && docker compose -f demo/docker-compose.yml up -d`.
