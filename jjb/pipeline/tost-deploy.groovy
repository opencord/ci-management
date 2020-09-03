pipeline {
    agent {
        docker { 
            image 'ubuntu:18.04' 
            args '-u root:sudo'
        }
    }
    environment {
        KUBECONFIG = credentials("${params.k8s_config}")
    }
    stages {
        stage('Install tools') {
            steps {
                sh '''
                set -x
                apt-get update -y
                apt-get install -y curl wget jq git
                
                # Install kubectl
                curl -LO "https://storage.googleapis.com/kubernetes-release/release/v1.18.0/bin/linux/amd64/kubectl"
                chmod +x ./kubectl
                mv ./kubectl /usr/local/bin/kubectl
                    
                # Test Kubectl & Rancher
                kubectl get nodes
                '''
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
            """
            cleanWs()
        }
    }    
}
