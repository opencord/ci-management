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
                sh '''
                set -x
                apt-get update -y
                apt-get install -y curl wget jq git unzip

                # Install kubectl
                curl -LO "https://storage.googleapis.com/kubernetes-release/release/v1.18.0/bin/linux/amd64/kubectl"
                chmod +x ./kubectl
                mv ./kubectl /usr/local/bin/kubectl

                # Test Kubectl & Rancher
                kubectl get nodes

                # Install
                wget https://releases.hashicorp.com/terraform/0.13.2/terraform_0.13.2_linux_amd64.zip
                unzip terraform_0.13.2_linux_amd64.zip
                mv terraform /usr/local/bin
                terraform version
                '''
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
                cd ${workspace}/${git_repo}/${terraform_dir}/tost
                if [ ! -z ${config_review} ] && [ ! -z ${config_patchset} ]; then
                    CFG_LAST2=\$(echo ${config_review} | tail -c 3)
                    git fetch "ssh://${git_server}:29418/${git_repo}" refs/changes/\${CFG_LAST2}/${config_review}/${config_patchset} && git checkout FETCH_HEAD
                fi
                GOOGLE_BACKEND_CREDENTIALS=${gcp} terraform init

                """
                }
            }
        }
        stage('Perform Terraform') {
             steps {
                sh """
                cd ${workspace}/${git_repo}/${terraform_dir}/tost
                GOOGLE_BACKEND_CREDENTIALS=${gcp} terraform destroy -var-file=${rancher_dev} -var 'cluster_name=${rancher_cluster}' -var-file=app_map.tfvars -auto-approve
                GOOGLE_BACKEND_CREDENTIALS=${gcp} terraform apply -var-file=${rancher_dev} -var 'cluster_name=${rancher_cluster}' -var-file=app_map.tfvars -auto-approve

                """
             }
        }

        stage('Install shared resources') {
            steps {
                sh(script: "date -u")
                    build(job: "${params.job_name}-shared")
            }
        }
        stage('Parallel Stage') {
            parallel {
                stage('onos') {
                    steps {
                        sh(script: "date -u")
                        build(job: "${params.job_name}-onos")
                    }
                }
                stage('stratum') {
                    steps {
                        sh(script: "date -u")
                        build(job: "${params.job_name}-stratum")
                    }
                }
                stage('telegraf') {
                    steps {
                        sh(script: "date -u")
                        build(job: "${params.job_name}-telegraf")
                    }
                }
            }
        }
    stage('E2E Testing') {
            options {
                timeout(time: 120, unit: "SECONDS")
            }
            steps {
                sh """
                echo ${params.target_server}
                timestamp=\$(date +%s)
                if [ ! -z "${params.target_server}" ]; then
                    kubectl delete --wait=true ds -l test=e2e || true
cat <<EOF > test.yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: ping-test-\${timestamp}
  labels:
    test: e2e-\${timestamp}
spec:
  selector:
    matchLabels:
      test: e2e-\${timestamp}
  template:
    metadata:
      labels:
        test: e2e-\${timestamp}
    spec:
      hostNetwork: true
      terminationGracePeriodSeconds: 5
      initContainers:
      - name: ping-test
        image: alpine:3.12.0
        command: ["sh", "-c", "until ping  ${params.target_server} -c1 -W 4; do echo 'ping retry'; sleep 3; done;"]
      containers:
      - name: ping-test-wait
        image: alpine:3.12.0
        command: ["sh", "-c", "sleep 100000"]
      restartPolicy: Always
EOF
                    kubectl apply -f test.yaml
                    number=\$(kubectl get --no-headers ds -ltest=e2e-\${timestamp} -o custom-columns=':.status.desiredNumberScheduled')
                    echo "\${number}"
                    until [ \${number} -eq \$(kubectl get pods -l test=e2e-\${timestamp} --no-headers  -o custom-columns=':.status.phase' | grep Running | wc -l) ]; do echo 'wait ping completed'; sleep 5; done;
                fi
                """
             }
        }
    }
    post {
        always {
            sh """
            if [ ! -z "${params.target_server}" ]; then
                kubectl delete -f test.yaml
            fi
            rm -rf ${workspace}/${git_repo}
            """
            cleanWs()
        }
    }
}
