# Webhook Relay Plugin for Jenkins

Receives webhooks via [Webhook Relay](https://webhookrelay.com) WebSocket tunnel and forwards them to Jenkins. This enables GitHub, GitLab, Bitbucket, and other webhook integrations **without exposing Jenkins to the public internet**.

## How It Works

The plugin maintains a persistent WebSocket connection to Webhook Relay's server (`wss://my.webhookrelay.com/v1/socket`). When a webhook is sent to your Webhook Relay input endpoint, it is forwarded through the WebSocket tunnel directly to your Jenkins instance. Jenkins' response (status code, headers, body) is then sent back to Webhook Relay's logs API so you can see the full request/response cycle in the Webhook Relay dashboard.

```
GitHub/GitLab/Bitbucket → Webhook Relay Cloud → [WebSocket] → Jenkins Plugin → Jenkins Webhook Endpoints
                                                                     ↓
                                              Webhook Relay Logs ← Response (status, headers, body)
```

## Requirements

- Jenkins 2.479.1 or newer
- Java 17 or 21
- A [Webhook Relay](https://webhookrelay.com) account with an API token

## Installation

### From Jenkins Plugin Manager

1. Go to **Manage Jenkins** > **Manage Plugins** > **Available**
2. Search for "Webhook Relay"
3. Install and restart Jenkins

### Manual Installation

1. Download the latest `.hpi` file from the [releases page](https://github.com/jenkinsci/webhook-relay-plugin/releases)
2. Go to **Manage Jenkins** > **Manage Plugins** > **Advanced**
3. Upload the `.hpi` file under "Deploy Plugin"
4. Restart Jenkins

## Configuration

### Quick Setup (Recommended)

1. Go to **Manage Jenkins** > **System** > **Webhook Relay**
2. Enter your **API Key** and **API Secret** ([get them here](https://my.webhookrelay.com/tokens))
3. Select your **SCM Webhook Preset** (GitHub, GitLab, Bitbucket, or Generic Webhook Trigger)
4. Click **Auto Setup** - this will automatically:
   - Create a Webhook Relay bucket (e.g., `jenkins-github`)
   - Create an input endpoint (the URL you'll use in your SCM settings)
   - Create an internal output pointing to the correct Jenkins webhook path
5. Copy the webhook URL from the success message and paste it into your SCM repository webhook settings
6. Check **Enable** and click **Save**

### Manual Setup

#### 1. Create a Webhook Relay Token

1. Log in to [Webhook Relay](https://my.webhookrelay.com/tokens)
2. Create a new token and note the **key** and **secret**

#### 2. Create a Bucket and Input

1. Go to [Buckets](https://my.webhookrelay.com/buckets)
2. Create a new bucket (e.g., `jenkins-webhooks`)
3. Note the input endpoint URL (e.g., `https://my.webhookrelay.com/v1/webhooks/...`)

#### 3. Configure Output Destination

Create an output in your bucket with the destination set to your Jenkins webhook endpoint:

| SCM Provider | Webhook Endpoint Path |
|---|---|
| GitHub | `http://localhost:8080/github-webhook/` |
| GitLab | `http://localhost:8080/project/YOUR_JOB_NAME` |
| Bitbucket | `http://localhost:8080/bitbucket-hook/` |
| Generic Webhook Trigger | `http://localhost:8080/generic-webhook-trigger/invoke` |

Make sure to set the output as **internal** so it's delivered through the WebSocket tunnel.

#### 4. Configure the Jenkins Plugin

1. Go to **Manage Jenkins** > **System**
2. Find the **Webhook Relay** section
3. Enter your **API Key** and **API Secret**
4. Enter your **Bucket names** (comma-separated) or leave empty to subscribe to all
5. Select the **SCM Webhook Preset** matching your setup
6. Check **Enable** to activate the connection
7. Click **Test Connection** to verify your credentials
8. Save

#### 5. Point Your SCM Webhooks to Webhook Relay

In your GitHub/GitLab/Bitbucket repository settings, set the webhook URL to your Webhook Relay input endpoint URL instead of your Jenkins URL.

## Response Logging

The plugin automatically sends Jenkins' response back to Webhook Relay after forwarding each webhook. This means you can see the full request/response cycle in the [Webhook Relay logs dashboard](https://my.webhookrelay.com/logs), including:

- HTTP status code from Jenkins
- Response headers
- Response body

This is useful for debugging webhook delivery issues.

## Troubleshooting

- **Connection Status shows "Authentication Failed"**: Verify your API key and secret are correct
- **Connection Status shows "Disconnected"**: The plugin will automatically reconnect with exponential backoff. Check Jenkins logs for details
- **Webhooks not triggering builds**: Ensure your output destination matches the expected Jenkins webhook endpoint path. Check Jenkins logs for forwarding errors
- **Response not showing in Webhook Relay logs**: Verify the webhook event has a valid `meta.id` field

## Development

### Prerequisites

- JDK 17 or 21 (JDK 25 has compatibility issues with Jenkins test harness)
- Maven 3.9+

```bash
# macOS
brew install openjdk@17 maven

# Set JAVA_HOME
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home
```

### Building

```bash
mvn clean verify
```

This compiles, runs tests, checks SpotBugs, and produces `target/webhook-relay.hpi`.

### Running Jenkins Locally with the Plugin

```bash
mvn hpi:run
```

This starts a local Jenkins instance at http://localhost:8080/jenkins/ with the plugin pre-installed. Any code changes to Jelly files are picked up on page refresh. Java changes require restarting `hpi:run`.

### Local Development Workflow

1. **Start Jenkins**: `mvn hpi:run`
2. **Configure the plugin**: Go to http://localhost:8080/jenkins/manage/configure and scroll to "Webhook Relay"
3. **Enter your Webhook Relay credentials** and select an SCM preset
4. **Click Auto Setup** to create a bucket/input/output, or configure manually
5. **Test with curl**: Send a test webhook to your Webhook Relay input URL:
   ```bash
   curl -X POST https://my.webhookrelay.com/v1/webhooks/YOUR_INPUT_ID \
     -H "Content-Type: application/json" \
     -d '{"ref":"refs/heads/main","repository":{"full_name":"test/repo"}}'
   ```
6. **Check Jenkins logs**: Look for `Forwarded webhook to ...` messages in the Jenkins console
7. **Check Webhook Relay logs**: Go to https://my.webhookrelay.com/logs to see the request/response cycle

### Running Tests Only

```bash
mvn test
```

### Debugging

To run Jenkins in debug mode (remote debugger on port 8000):

```bash
mvnDebug hpi:run
```

Then attach your IDE debugger to `localhost:8000`.

### Project Structure

```
src/main/java/com/webhookrelay/jenkins/
  ConnectionManager.java      - WebSocket lifecycle, reconnect with backoff
  ConnectionStatus.java        - Connection state enum
  LogsUpdater.java            - PUT response data to Webhook Relay logs API
  WebhookForwarder.java       - Forward webhooks to Jenkins via HTTP
  WebhookRelayAPI.java        - REST API client (buckets, inputs, outputs)
  WebhookRelayConnection.java - WebSocket client (TLS, callbacks)
  WebhookRelayPlugin.java     - GlobalConfiguration, UI, auto-setup
  model/
    AuthMessage.java           - WebSocket auth message
    ForwardResponse.java       - Jenkins response capture
    SubscribeMessage.java      - WebSocket subscribe message
    WebhookEvent.java          - Incoming webhook event POJO
src/main/resources/.../config.jelly  - Manage Jenkins config UI
```

## Publishing to plugins.jenkins.io

1. Open a hosting request at [jenkins-infra/repository-permissions-updater](https://github.com/jenkins-infra/repository-permissions-updater)
2. The Jenkins hosting team forks the repo into the `jenkinsci` org
3. Add a `Jenkinsfile` with `buildPlugin()` for CI
4. Release: `mvn release:prepare release:perform`

## License

MIT License. See [LICENSE](LICENSE) for details.
