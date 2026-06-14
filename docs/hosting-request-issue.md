### Repository URL

https://github.com/webhookrelay/jenkins-webhook-tunnel-plugin

### New Repository Name

webhook-relay-plugin

### Description

The Webhook Relay plugin lets Jenkins receive GitHub, GitLab and Bitbucket webhooks without
exposing Jenkins to the public internet. It opens an outbound WebSocket connection to a
Webhook Relay bucket, subscribes to it, and forwards each received webhook to the matching
Jenkins endpoint (`/github-webhook/`, `/bitbucket-hook/`, the Generic Webhook Trigger, etc.).
Unlike running the standalone `relay` CLI agent, the forwarder runs inside Jenkins, and
because delivery goes through a bucket every request is recorded together with the response
Jenkins returned. No existing plugin provides Webhook Relay integration.

### GitHub users to have commit permission

@rusenask

### Jenkins project users to have release permission

rusenask

### Automated release via GitHub Actions (recommended)

Yes
