/*
 * CI build for the Webhook Relay plugin, run on ci.jenkins.io.
 * See https://github.com/jenkins-infra/pipeline-library
 */
buildPlugin(
    useContainerAgent: true,
    configurations: [
        [platform: 'linux',   jdk: 21],
        [platform: 'windows', jdk: 25],
    ]
)
