pipeline {
    agent {
        docker {
            image 'ubuntu:18.04'
            args '-u root:sudo'
        }
    }
    environment {
        KUBECONFIG = credentials("${params.k8s_config}")
        onos_password = credentials("${params.onos_password}")
        git_password = credentials("${params.git_password_env}")
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

                # Install yq
                wget https://github.com/mikefarah/yq/releases/download/3.4.0/yq_linux_amd64 -O /usr/bin/yq &&\
                chmod +x /usr/bin/yq
                yq --help

                # Install kubectl
                curl -LO "https://storage.googleapis.com/kubernetes-release/release/v1.18.0/bin/linux/amd64/kubectl"
                chmod +x ./kubectl
                mv ./kubectl /usr/local/bin/kubectl

                # Install terraform
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
                cd ${workspace}/${git_repo}/${terraform_dir}/tost/onos
                if [ ! -z ${config_review} ] && [ ! -z ${config_patchset} ]; then
                    CFG_LAST2=\$(echo ${config_review} | tail -c 3)
                    git fetch "ssh://${git_server}:29418/${git_repo}" refs/changes/\${CFG_LAST2}/${config_review}/${config_patchset} && git checkout FETCH_HEAD
                    cp onos.yaml tmp.yaml

cat <<EOF >> config.yaml
config:
  review: ${config_review}
  patchset: ${config_patchset}
EOF

                    yq merge tmp.yaml config.yaml > onos.yaml
                fi
                GOOGLE_BACKEND_CREDENTIALS=${gcp} terraform init
                """
                }
            }
        }

        stage('Uninstall Apps') {
            options {
                timeout(time: 90, unit: "SECONDS")
            }
            steps {
                sh """
                cd ${workspace}/${git_repo}/${terraform_dir}/tost/onos
                GOOGLE_BACKEND_CREDENTIALS=${gcp} terraform destroy -var-file=${rancher_dev} -var 'cluster_name=${rancher_cluster}' -var 'project_name=tost' -var-file=app_map.tfvars -auto-approve
                """
             }
        }
        stage('Remove resources') {
            options {
                timeout(time: 300, unit: "SECONDS")
            }
            steps {
                sh """
                pvcs=\$(kubectl -n onos-tost get pvc -lapp=onos-tost-atomix -o name)
                for pv in \${pvcs}; do kubectl -n onos-tost delete \${pv}; done

                kubectl -n ${onos_ns} delete secret onos-git-secret || true
                kubectl -n ${onos_ns} delete secret onos-secret || true
                """
             }
        }
        stage('Install apps') {
            options {
                timeout(time: 600, unit: "SECONDS")
            }
            steps {
                sh """
                cd ${workspace}/${git_repo}/${terraform_dir}/tost/onos
                GOOGLE_BACKEND_CREDENTIALS=${gcp} terraform apply -var-file=${rancher_dev} -var 'cluster_name=${rancher_cluster}' -var 'project_name=tost' -var-file=app_map.tfvars -auto-approve
                """
             }
        }
        stage('Push Secrets') {
            steps {
                sh """

                kubectl -n ${onos_ns} create secret generic onos-git-secret --from-literal=username=${git_user} --from-literal=password=${git_password}
                kubectl -n ${onos_ns} create secret generic onos-secret --from-literal=username=${onos_user} --from-literal=password=${onos_password}
                """
            }
        }

    }
    post {
        always {

            sh """
            rm -rf ${workspace}/${git_repo}
            """
            cleanWs()
        }
    }
}
