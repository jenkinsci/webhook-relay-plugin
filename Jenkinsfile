// Demo pipeline used to show a build being triggered by a GitHub webhook that is
// delivered to Jenkins through a Webhook Relay bucket (no public Jenkins required).
pipeline {
    agent any

    triggers {
        // Build when GitHub sends a push event through the Webhook Relay bucket.
        githubPush()
    }

    stages {
        stage('Checkout') {
            steps {
                echo "Triggered by a webhook forwarded via Webhook Relay 🎉"
                echo "Build #${env.BUILD_NUMBER} on ${env.JOB_NAME}"
            }
        }
        stage('Build') {
            steps {
                echo 'Building the Webhook Relay Jenkins plugin...'
                sh 'echo "mvn -q -B clean package (demo placeholder)"'
            }
        }
    }

    post {
        success {
            echo 'Webhook → Webhook Relay bucket → plugin → Jenkins build: success ✅'
        }
    }
}
