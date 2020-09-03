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
        git_password = credentials("${params.git_password_env}")
        onos_password = credentials("${params.onos_password}")
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
                    echo "config.review: ${config_review}" >> deployment-configs/aether/apps/${config_env}/onos-ans.yml
                    echo "config.patchset: ${config_patchset}" >> deployment-configs/aether/apps/${config_env}/onos-ans.yml
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
        stage('Push Secrets') {
            steps {
                sh '''
                
                rancher namespaces ls | grep ${onos_ns} || rancher namespaces create ${onos_ns}
                
                kubectl -n ${onos_ns} delete secret git-secret --ignore-not-found=true
                kubectl -n ${onos_ns} create secret generic git-secret --from-literal=username=${git_user} --from-literal=password=${git_password}
                kubectl -n ${onos_ns} delete secret aether-registry-credential --ignore-not-found=true
                kubectl -n ${onos_ns} create secret docker-registry aether-registry-credential  --docker-server=${registry_server} --docker-username=${registry_user} --docker-password=${registry_password}


                
                kubectl -n ${onos_ns} delete secret onos-secret --ignore-not-found=true
                kubectl -n ${onos_ns} create secret generic onos-secret --from-literal=username=${onos_user} --from-literal=password=${onos_password}
                '''
            }
        }
        
        stage('Uninstall Apps') {
            options {
                timeout(time: 90, unit: "SECONDS")
            }            
            steps {
                sh '''
                for app in $(rancher apps ls -q | grep -E '(onos-tost)'); do rancher apps delete $app; done
                
                until [ "$(rancher apps ls -q | grep -E '(onos-tost)')" = "" ]; do echo "wait deleted apps"; rancher apps ls ; sleep 1; done
                '''
             }
        }  
        stage('Remove PVC') {
            options {
                timeout(time: 300, unit: "SECONDS")
            }            
            steps {
                sh '''
                pvcs=$(kubectl -n onos-tost get pvc -lapp=onos-tost-atomix -o name)
                for pv in $pvcs; do kubectl -n onos-tost delete $pv; done
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

                until rancher apps install --answers onos-ans.yml --namespace ${onos_ns} cattle-global-data:${onos_catalog_name}-onos-tost onos-tost; do :; done
                apps=$(rancher apps -q | grep onos-tost)
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

