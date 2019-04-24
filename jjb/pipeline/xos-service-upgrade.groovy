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

    stage('patch') {
      steps {
        sh '''
           pushd cord
           PROJECT_PATH=\$(xmllint --xpath "string(//project[@name=\\\"${gerritProject}\\\"]/@path)" .repo/manifest.xml)
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

    stage('Install XOS w/Service') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           pushd cord/helm-charts
           helm dep update xos-core
           helm install xos-core -n xos-core

           git clone https://gerrit.opencord.org/helm-repo-tools
           helm-repo-tools/wait_for_pods.sh

           #install service
           helm dep update xos-services/${gerritProject}
           helm install xos-services/${gerritProject} -n ${gerritProject}
           popd
           """
      }
    }
    stage('Verify') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -ex -o pipefail
           export DOCKER_TAG=\$(cat $WORKSPACE/cord/orchestration/xos/VERSION)

           #wait for xos-core and models to be loaded
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"core\\").state'| grep -q present; do echo 'Waiting for Core to be loaded'; sleep 5; done"
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"${gerritProject}\\").state'| grep -q present; do echo 'Waiting for Core to be loaded'; sleep 5; done"
           http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq ".services[] | select(.name==\\"core\\").version"| grep -q \$DOCKER_TAG

           ## get pod logs
           for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
           do
             kubectl logs \$pod> $WORKSPACE/\$pod.log;
           done
           """
      }
    }
    stage('Build/Install New Service') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail
           pushd $WORKSPACE/cord/orchestration/xos-services/${gerritProject}

           export DOCKER_REPOSITORY=xosproject/
           export DOCKER_TAG=test
           export DOCKER_BUILD_ARGS=--no-cache

           cd $WORKSPACE/cord/orchestration/xos-services/${gerritProject}
           make docker-build

           #install newservice
           cd $WORKSPACE/cord/helm-charts
           helm upgrade --set image.tag=test \
                        --set image.pullPolicy=Never \
                         ${gerritProject} xos-services/${gerritProject}
           popd
           """
      }
    }
    stage('Verify Service Upgrade') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -ex -o pipefail

           #wait for xos-core and models to be loaded
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"${gerritProject}\\").state'| grep -q present; do echo 'Waiting for Core to be loaded'; sleep 5; done"
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"core\\").state'| grep -q present; do echo 'Waiting for Core to be loaded'; sleep 5; done"
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"att-workflow-driver\\").version'| grep -q test; do echo 'Waiting for Service Version Check'; sleep 5; done"
           ## get pod logs
           for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
           do
             kubectl logs \$pod> $WORKSPACE/\$pod.log;
           done
           """
      }
    }
  }
  post {
    always {
      sh """
         kubectl get pods --all-namespaces
         kubectl describe pods
         http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status
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
         step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "kailash@opennetworking.org, scottb@opennetworking.org", sendToIndividuals: false])

    }
  }
}
