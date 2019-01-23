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

    stage('Install/Test BBSIM w/SEBA') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           pushd cord/helm-charts

           helm install -f examples/kafka-single.yaml --version 0.8.8 -n cord-kafka incubator/kafka

           git clone https://gerrit.opencord.org/helm-repo-tools
           helm-repo-tools/wait_for_pods.sh

           helm dep up xos-core
           helm install xos-core -n xos-core

           helm dep update xos-profiles/seba-services
           helm install \${helm_install_args}  xos-profiles/seba-services
           JOBS_TIMEOUT=900 ./helm-repo-tools/wait_for_jobs.sh

           helm dep update workflows/att-workflow
           helm install workflows/att-workflow -n att-workflow

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
           helm test --timeout 1000 bbsim
           popd
           """
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
