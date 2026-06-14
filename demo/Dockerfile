# Demo Jenkins image with the Webhook Relay plugin's dependencies pre-installed.
# Build the plugin first (`mvn -q clean package`) so target/webhook-relay.hpi exists.
FROM jenkins/jenkins:lts-jdk17

# Skip the setup wizard; the admin user is provisioned via Configuration as Code.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc.yaml

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugins -f /usr/share/jenkins/ref/plugins.txt

COPY casc.yaml /var/jenkins_home/casc.yaml
