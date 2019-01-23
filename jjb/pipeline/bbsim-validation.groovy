// Copyright 2017-present Open Networking Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// chart-api-test-helm.groovy
// Checks functionality of the helm-chart, without overriding the version/tag used

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  stages {

    stage('repo') {
      steps {
        checkout(changelog: false, \
          poll: false,
          scm: [$class: 'RepoScm', \
            manifestRepositoryUrl: "${params.manifestUrl}", \
            manifestBranch: "${params.manifestBranch}", \
            currentBranch: true, \
            destinationDir: 'cord', \
            forceSync: true,
            resetFirst: true, \
            quiet: true, \
            jobs: 4, \
            showAllChanges: true] \
          )
      }
    }

    stage('minikube') {
      steps {
        /* see https://github.com/kubernetes/minikube/#linux-continuous-integration-without-vm-support */
        sh '''
           export MINIKUBE_WANTUPDATENOTIFICATION=false
           export MINIKUBE_WANTREPORTERRORPROMPT=false
           export CHANGE_MINIKUBE_NONE_USER=true
           export MINIKUBE_HOME=$HOME
           mkdir -p $HOME/.kube || true
           touch $HOME/.kube/config
           export KUBECONFIG=$HOME/.kube/config
           sudo -E /usr/bin/minikube start --vm-driver=none
           '''
        script {
          timeout(3) {
            waitUntil {
              sleep 5
              def kc_ret = sh script: "kubectl get po", returnStatus: true
              return (kc_ret == 0);
            }
          }
        }
      }
    }

    stage('helm') {
      steps {
        sh '''
           helm init
           sleep 60
           helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com/
           helm repo add cord https://charts.opencord.org
           helm repo update
           '''
      }
    }

    stage('Build BBSIM') {
      steps {
        sh '''
           git clone https://github.com/opencord/voltha-bbsim
           cd voltha-bbsim/
           make docker
           '''
      }
    }

    stage('Install BBSIM w/SEBA') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           pushd cord/helm-charts

           helm install -f examples/kafka-single.yaml --version 0.13.3 -n cord-kafka incubator/kafka

           git clone https://gerrit.opencord.org/helm-repo-tools
           helm-repo-tools/wait_for_pods.sh

           helm upgrade --install etcd-operator --version 0.8.3 stable/etcd-operator
           JOBS_TIMEOUT=900 ./helm-repo-tools/wait_for_jobs.sh

           helm dep up voltha
           helm install voltha -n voltha
           JOBS_TIMEOUT=900 ./helm-repo-tools/wait_for_jobs.sh

           helm dep up xos-core
           helm install xos-core -n xos-core

           helm dep update xos-profiles/seba-services
           helm install \${helm_install_args}  xos-profiles/seba-services
           JOBS_TIMEOUT=900 ./helm-repo-tools/wait_for_jobs.sh

           helm dep update workflows/att-workflow
           helm install workflows/att-workflow -n att-workflow

           helm install onos -n onos

           helm repo add incubator http://storage.googleapis.com/kubernetes-charts-incubator

           # wait for services to load
           JOBS_TIMEOUT=900 ./helm-repo-tools/wait_for_jobs.sh
           sleep 300
           echo "# Checking helm deployments"
           kubectl get pods
           helm list

           helm install bbsim --set images.bbsim.tag:latest -n bbsim

           for hchart in \$(helm list -q);
           do
             echo "## 'helm status' for chart: \${hchart} ##"
             helm status "\${hchart}"
           done
           popd
           """
      }
    }
    stage('Wait for OLT+ONU') {
      steps {
        sh '''
           #!/usr/bin/env bash
           set -eu -o pipefail
           timeout 300 bash -c "until http -a karaf:karaf --ignore-stdin --check-status GET http://127.0.0.1:30120/onos/v1/configuration/org.opencord.olt.impl.Olt"
           http -a karaf:karaf --ignore-stdin POST http://127.0.0.1:30120/onos/v1/configuration/org.opencord.olt.impl.Olt defaultVlan=65535
           timeout 300 bash -c "until http GET http://127.0.0.1:30125/health|jq '.state'|grep -q HEALTHY; do echo 'Waiting for VOLTHA to be HEALTHY'; sleep 10; done"
           timeout 300 bash -c "until http GET http://127.0.0.1:30125/api/v1/devices|jq '.items[].admin_state'|grep ENABLED|wc -l|grep -q 2; do echo 'Waiting for OLT and ONU to be enabled in VOLTHA'; sleep 10; done"
           timeout 300 bash -c "until http -a karaf:karaf GET http://127.0.0.1:30120/onos/v1/devices|jq '.devices[].available'|grep true|wc -l|grep -q 2; do echo 'Waiting for VOLTHA logical device and agg switch to be available in ONOS'; sleep 10; done"
           '''
      }
    }
    stage('Load BBSIM Tosca') {
      steps {
        sh '''
           #!/usr/bin/env bash
           set -eu -o pipefail

           pushd cord/helm-charts

           curl -H "xos-username: admin@opencord.org" -H "xos-password: letmein" -X POST --data-binary @examples/bbsim-dhcp.yaml http://127.0.0.1:30007/run
           curl -H "xos-username: admin@opencord.org" -H "xos-password: letmein" -X POST --data-binary @examples/bbsim-16.yaml http://127.0.0.1:30007/run

           popd
           '''
      }
    }
    stage('Load BBSIM Tosca') {
      steps {
        sh '''
           #!/usr/bin/env bash
           set -eu -o pipefail

           helm test --timeout 1000 bbsim || true

           popd
           '''
      }
    }
  }
  post {
    always {
      sh '''

         kubectl logs bbsim-api-test --namespace default
         kubectl get pods --all-namespaces

         # copy robot logs
         if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
         cp -r /tmp/helm_test_bbsim_logs_*/*ml ./RobotLogs

         echo "# removing helm deployments"
         kubectl get pods
         helm list

         for hchart in \$(helm list -q);
         do
           echo "## Purging chart: \${hchart} ##"
           helm delete --purge "\${hchart}"
         done

         sudo minikube delete
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/output*.xml',
            outputPath: '.',
            passThreshold: 100,
            reportFileName: 'RobotLogs/report*.html',
            unstableThreshold: 0]);
         step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "kailash@opennetworking.org", sendToIndividuals: false])

    }
  }
}
