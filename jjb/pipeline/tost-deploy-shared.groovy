pipeline {
    agent {
        docker {
            image 'ubuntu:18.04'
            args '-u root:sudo'
        }
    }
    environment {
        KUBECONFIG = credentials("${params.k8s_config}")
        registry_password = credentials("${params.registry_password_env}")
    }
    stages {
        stage('Install tools') {
            steps {
                sh '''
                set -x
                apt-get update -y
                apt-get install -y curl

                # Install kubectl
                curl -LO "https://storage.googleapis.com/kubernetes-release/release/v1.18.0/bin/linux/amd64/kubectl"
                chmod +x ./kubectl
                mv ./kubectl /usr/local/bin/kubectl

                # Test Kubectl & Rancher
                kubectl get nodes
                '''
            }
        }
        stage('Perform Terraform') {
             steps {
                sh """
                kubectl -n ${target_namespace} create secret docker-registry aether-registry-credential  --docker-server=${registry_server} --docker-username=${registry_user} --docker-password=${registry_password}
                """
             }
        }
    }
    post {
        always {
            cleanWs()
        }
    }
}
