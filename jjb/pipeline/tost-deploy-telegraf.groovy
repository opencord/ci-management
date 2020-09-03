pipeline {
    agent {
        docker { 
            image 'ubuntu:18.04' 
            args '-u root:sudo'
        }
    }
    environment {
        KUBECONFIG = credentials("${params.k8s_config}")
        git_password = credentials("${params.git_password_env}")
        rancher_token = credentials("${params.rancher_api_env}")
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
                    
                # Install rancher    
                wget https://github.com/rancher/cli/releases/download/v2.4.5/rancher-linux-amd64-v2.4.5.tar.gz
                tar -xvf rancher-linux-amd64-v2.4.5.tar.gz
                mv rancher-v2.4.5/rancher /usr/local/bin
                
                rm rancher-linux-amd64-v2.4.5.tar.gz
                rm -rf rancher-v2.4.5
                # Test Kubectl & Rancher
                KUBE_CONFIG=$KUBECONFIG kubectl get nodes
                rancher --version
                '''
            }
        }
        stage('Clone Config Repo') {
            options {
                timeout(time: 10, unit: "SECONDS")
            }            
            steps {
                sh '''
                git clone https://${git_user}:${git_password}@${git_server}/${git_repo}
                if [ ! -z ${config_review} ] && [ ! -z ${config_patchset} ]; then
                    cd ${git_repo}
                    CFG_LAST2=$(echo ${config_review} | tail -c 3)
                    git fetch "https://${git_user}:${git_password}@${git_server}/a/${git_repo}" refs/changes/${CFG_LAST2}/${config_review}/${config_patchset} && git checkout FETCH_HEAD
                    git checkout FETCH_HEAD
                    cd ..
                fi
                '''
             }
        }            
        stage('Login Rancher') {
            steps {
                sh '''
                rancher login ${rancher_server} --token ${rancher_token} --context ${rancher_context}:${rancher_project}
                '''
             }
        }
        stage('Uninstall Apps') {
            options {
                timeout(time: 90, unit: "SECONDS")
            }            
            steps {
                sh '''
                for app in $(rancher apps ls -q | grep -E '(telegraf)'); do rancher apps delete $app; done
                
                until [ "$(rancher apps ls -q | grep -E '(telegraf)')" = "" ]; do echo "wait deleted apps"; rancher apps ls ; sleep 1; done
                '''
             }
        }  
       stage('Install apps') {
            options {
                timeout(time: 600, unit: "SECONDS")
            }
            steps {
                sh '''
                cd ${git_repo}/deployment-configs/aether/apps/${config_env}/
                until rancher apps install --answers telegraf-ans.yml --namespace ${telegraf_ns} cattle-global-data:influxdata-telegraf telegraf; do :; done
                apps=$(rancher apps -q | grep telegraf)
                for app in $apps; do until rancher wait $app --timeout 20; do :; done; rancher apps ls; done                
                '''
             }
        }          
        
    }
    post { 
        always { 
            cleanWs()
      }
    }    
}

