pipeline {
    agent {
        docker {
            image 'ubuntu:18.04'
            args '-u root:sudo'
        }
    }
    environment {
        KUBECONFIG = credentials("${params.k8s_config}")
        gcp = credentials("${params.gcp_credential}")
        rancher_dev = credentials("${params.rancher_api_env}")
    }
    stages {
        stage('Install tools') {
            steps {
                sh """
                set -x
                apt-get update -y
                apt-get install -y curl wget jq git unzip

                # Install kubectl
                curl -LO "https://storage.googleapis.com/kubernetes-release/release/v1.18.0/bin/linux/amd64/kubectl"
                chmod +x ./kubectl
                mv ./kubectl /usr/local/bin/kubectl

                wget https://releases.hashicorp.com/terraform/0.13.2/terraform_0.13.2_linux_amd64.zip
                unzip terraform_0.13.2_linux_amd64.zip
                mv terraform /usr/local/bin
                terraform version
                """
            }
        }
        stage('Init Terraform') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: "aether_jenkins", keyFileVariable: 'keyfile')]) {
                sh """#!/bin/bash
                set -x
                mkdir -p ~/.ssh
                ssh-keyscan -t rsa -p 29418 ${git_server} >> ~/.ssh/known_hosts
cat <<EOF > ~/.ssh/config
Host ${git_server}
  User ${git_user}
  Hostname ${git_server}
  Port 29418
  IdentityFile ${keyfile}
EOF
                git clone "ssh://${git_server}:29418/${git_repo}"
                cd ${git_repo}/${terraform_dir}/tost/telegraf
                GOOGLE_BACKEND_CREDENTIALS=${gcp} terraform init
                """
                }
            }
        }
        stage('Clone Config Repo') {
            options {
                timeout(time: 10, unit: "SECONDS")
            }
            steps {
                sh """
                if [ ! -z ${config_review} ] && [ ! -z ${config_patchset} ]; then
                    cd ${git_repo}
                    CFG_LAST2=\$(echo ${config_review} | tail -c 3)
                    git fetch "ssh://@${git_server}:29418/${git_repo}" refs/changes/\${CFG_LAST2}/${config_review}/${config_patchset} && git checkout FETCH_HEAD
                    git checkout FETCH_HEAD
                    cd ..
                fi
                """
             }
        }
        stage('Uninstall Apps') {
            options {
                timeout(time: 90, unit: "SECONDS")
            }
            steps {
                sh """
                cd ${git_repo}/${terraform_dir}/tost/telegraf
                GOOGLE_BACKEND_CREDENTIALS=${gcp} terraform destroy -var-file=${rancher_dev} -var 'cluster_name=${rancher_cluster}' -var 'project_name=tost' -var-file=app_map.tfvars -auto-approve
                """
             }
        }
       stage('Install apps') {
            options {
                timeout(time: 600, unit: "SECONDS")
            }
            steps {
                sh """
                cd ${git_repo}/${terraform_dir}/tost/telegraf
                GOOGLE_BACKEND_CREDENTIALS=${gcp} terraform apply -var-file=${rancher_dev} -var 'cluster_name=${rancher_cluster}'  -var 'project_name=tost'  -var-file=app_map.tfvars -auto-approve
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
