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
    label "${params.buildNode}"
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

    stage('patch') {
      steps {
        sh '''
           pushd cord
           PROJECT_PATH=\$(xmllint --xpath "string(//project[@name=\\\"${gerritProject}\\\"]/@path)" .repo/manifests/default.xml)
           repo download "\$PROJECT_PATH" "${gerritChangeNumber}/${gerritPatchsetNumber}"
           popd
           '''
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
              def kc_ret = sh script: "kubectl get pods", returnStatus: true
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
           '''
      }
    }

    stage ('Build XOS Core and TestService') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           export DOCKER_REPOSITORY=xosproject/
           export DOCKER_TAG=\$(cat $WORKSPACE/cord/orchestration/xos/VERSION)
           export DOCKER_BUILD_ARGS=--no-cache

           cd $WORKSPACE/cord/orchestration/xos
           make docker-build

           cd $WORKSPACE/cord/orchestration/xos/testservice
           make docker-build
           """
      }
    }

    stage('Install XOS w/TestService') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           export DOCKER_TAG=\$(cat $WORKSPACE/cord/orchestration/xos/VERSION)

           pushd cord/helm-charts
           helm dep update xos-core
           helm install --set images.xos_core.tag=\$DOCKER_TAG,images.xos_core.pullPolicy=Never xos-core -n xos-core

           helm-repo-tools/wait_for_pods.sh

           #install testservice
           cd $WORKSPACE/cord/orchestration/xos/testservice/helm-charts
           helm install --set testservice_synchronizerImage=xosproject/testservice-synchronizer:\$DOCKER_TAG \
                        --set imagePullPolicy=Never \
                        testservice -n testservice
           popd
           """
      }
    }
    stage('Wait for Core') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -ex -o pipefail

           #wait for xos-core and models to be loaded
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/core/sites |jq '.items[0].name'|grep -q mysite; do echo 'Waiting for API To be up'; sleep 10; done"
           """
      }
    }
    stage('Test Core') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -ex -o pipefail

           pushd cord/test/cord-tester
           make venv_cord
           source venv_cord/bin/activate
           cd src/test/cord-api/Tests/xos-test-service

           robot -e notready test-service.robot || true
           popd
           """
      }
    }
    stage ('Archive Artifacts') {
      when { expression { return params.ArchiveLogs } }
      steps {
          sh '''
           kubectl get pods --all-namespaces
           ## get default pod logs
           for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
           do
             kubectl logs \$pod> $WORKSPACE/\$pod.log;
           done
           '''
      }
    }
  }
  post {
    always {
      sh """
         kubectl get pods --all-namespaces

         # copy robot logs
         if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
         cp -r $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/xos-test-service/*ml ./RobotLogs
         echo "# removing helm deployments"
         kubectl get pods
         helm list

         for hchart in \$(helm list -q);
         do
           echo "## Purging chart: \${hchart} ##"
           helm delete --purge "\${hchart}"
         done

         sudo minikube delete
         """
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/output*.xml',
            outputPath: '.',
            passThreshold: 100,
            reportFileName: 'RobotLogs/report*.html',
            unstableThreshold: 0]);
         archiveArtifacts artifacts: '*.log'
         step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "kailash@opennetworking.org, teo@opennetworking.org", sendToIndividuals: false])

    }
  }
}
