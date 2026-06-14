# Publishing to plugins.jenkins.io

The plugin is hosting-ready. Getting it onto the official
[Jenkins plugin site](https://plugins.jenkins.io) is a one-time **hosting request** to the
Jenkins project. It is filed as a **GitHub issue** (not a pull request) in
[`jenkins-infra/repository-permissions-updater`](https://github.com/jenkins-infra/repository-permissions-updater/issues/new?assignees=&labels=hosting-request&template=1-hosting-request.yml).

## Prerequisites (only you can do these)

1. **Make this repository public.** Hosting requires a public source repository.
2. **Have a Jenkins community account.** Register at <https://accounts.jenkins.io> (this is
   the identity used for releases on Artifactory/Jira — separate from your GitHub login).

## Hosting request — field values

| Form field | Value |
|---|---|
| **Repository URL** | `https://github.com/webhookrelay/jenkins-webhook-tunnel-plugin` |
| **New Repository Name** | `webhook-relay-plugin` |
| **GitHub users to have commit permission** | `rusenask` (add any co-maintainers) |
| **Jenkins project users to have release permission** | your accounts.jenkins.io username |
| **Automated release via GitHub Actions** | Yes (recommended — CD via the Jenkins release workflow) |

**Description:**

> The Webhook Relay plugin lets Jenkins receive GitHub, GitLab and Bitbucket webhooks
> without exposing Jenkins to the public internet. It opens an outbound WebSocket to a
> Webhook Relay bucket, subscribes to it, and forwards each received webhook to the matching
> Jenkins endpoint (`/github-webhook/`, `/bitbucket-hook/`, the Generic Webhook Trigger,
> etc.). Unlike running the standalone `relay` CLI agent, the forwarder runs inside Jenkins,
> and because delivery goes through a bucket every request is recorded with the response
> Jenkins returned. No existing plugin provides Webhook Relay integration.

## Hosting requirements checklist (already satisfied)

- [x] Public source on GitHub (once made public)
- [x] `pom.xml` with `io.jenkins.plugins` groupId, artifactId `webhook-relay`, release parent POM
- [x] Plugin name does not contain "Jenkins"/"Hudson"; artifactId is `webhook-relay` (free on the update center)
- [x] `Jenkinsfile` running `buildPlugin(...)` for ci.jenkins.io
- [x] MIT `LICENSE` and `<licenses>` in the POM
- [x] README with usage documentation and screenshots
- [x] No bundled binaries except documentation images

## After the request

A member of the Hosting team reviews within a few days; the automated checker validates the
POM/repo. On approval the repo is forked to `jenkinsci/webhook-relay-plugin`, after which you
push there and cut the first release (`1.0`) via the Jenkins release process.
