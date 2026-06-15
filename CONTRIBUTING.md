# Contributing

## Prerequisites

- JDK 17 or 21
- Maven 3.9+

## Build and test

```bash
mvn clean verify   # compile, run tests + SpotBugs, build target/webhook-relay.hpi
```

## Run Jenkins locally with the plugin

```bash
mvn hpi:run        # http://localhost:8080/jenkins/
```

Java changes require restarting `hpi:run`; Jelly changes are picked up on refresh.

There is also a Docker demo under [`demo/`](demo/) that starts a Jenkins with the plugin's
dependencies pre-installed — see [`demo/README.md`](demo/README.md).

## Project structure

```
src/main/java/com/webhookrelay/jenkins/
  ConnectionManager.java      WebSocket lifecycle, reconnect with backoff
  ConnectionStatus.java       Connection state enum
  LogsUpdater.java            Report the Jenkins response back to the bucket log
  WebhookForwarder.java       Replay the webhook against the Jenkins endpoint
  WebhookRelayAPI.java        REST client (buckets, inputs, outputs)
  WebhookRelayConnection.java WebSocket client
  WebhookRelayPlugin.java     Global configuration, UI, bucket -> URL lookup
  model/                      JSON message POJOs
demo/                         Docker + Configuration-as-Code demo
```

## Releases

Releases are produced via the Jenkins [CD process](https://www.jenkins.io/redirect/continuous-delivery-of-plugins/)
(see `.github/workflows/cd.yaml`). Merged pull requests labelled for release are published
automatically.
