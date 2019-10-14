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

def serviceName = "${gerritProject}"

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
          script {
          if (serviceName == "olt-service") {
            serviceName = "volt"
          }
          else if (serviceName == "onos-service") {
            serviceName = "onos"
          }
          else if (serviceName == "kubernetes-service") {
            serviceName = "kubernetes"
          }
        }
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           pushd cord/helm-charts
           helm dep update xos-core
           helm install xos-core -n xos-core

           helm-repo-tools/wait_for_pods.sh

           #install service
           helm dep update xos-services/${serviceName}
           helm install xos-services/${serviceName} -n ${serviceName}
           popd
           """
      }
    }
    stage('Verify') {
      steps {
        echo "serviceName: ${serviceName}"
        sh """
           #!/usr/bin/env bash
           set -ex -o pipefail
           export DOCKER_TAG=\$(cat $WORKSPACE/cord/orchestration/xos/VERSION)

           #wait for xos-core and models to be loaded
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"core\\").state'| grep -q present; do echo 'Waiting for Core to be loaded'; sleep 5; done"
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"${serviceName}\\").state'| grep -q present; do echo 'Waiting for Core to be loaded'; sleep 5; done"

           ## get pod logs
           for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
           do
             kubectl logs \$pod> $WORKSPACE/\$pod.log;
           done
           """
      }
    }
    stage('Generate Model API Tests') {
      steps {
        sh """
           CORE_CONTAINER=\$(docker ps | grep k8s_xos-core | awk '{print \$1}')
           cd $WORKSPACE/cord/test/cord-tester
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosserviceupgradetest.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosserviceupgradetest.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosstaticlibrary.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosstaticlibrary.xtarget

           export testname=_service_api.robot
           export library=_library.robot

           SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
           echo \$SERVICES

           for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosserviceupgradetest.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$testname; done

           for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosstaticlibrary.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$library; done
           ls -al $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/
           """
      }
    }

    stage('Test Pre-Upgrade') {
      steps {
        sh """
           pushd cord/test/cord-tester/src/test/cord-api/Tests

           CORE_CONTAINER=\$(docker ps | grep k8s_xos-core | awk '{print \$1}')
           CHAM_CONTAINER=\$(docker ps | grep k8s_xos-chameleon | awk '{print \$1}')
           XOS_CHAMELEON=\$(docker exec \$CHAM_CONTAINER ip a | grep -oE "([0-9]{1,3}\\.){3}[0-9]{1,3}\\b" | grep 172)

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Properties/
           sed -i \"s/^\\(SERVER_IP = \\).*/\\1\'\$XOS_CHAMELEON\'/\" RestApiProperties.py
           sed -i \"s/^\\(SERVER_PORT = \\).*/\\1\'9101\'/\" RestApiProperties.py
           sed -i \"s/^\\(XOS_USER = \\).*/\\1\'admin@opencord.org\'/\" RestApiProperties.py
           sed -i \"s/^\\(XOS_PASSWD = \\).*/\\1\'letmein\'/\" RestApiProperties.py
           sed -i \"s/^\\(PASSWD = \\).*/\\1\'letmein\'/\" RestApiProperties.py

           export testname=_service_api.robot
           export library=_library.robot
           SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
           echo \$SERVICES

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests
           for i in \$SERVICES; do bash -c "robot -v SETUP_FLAG:Setup -i create -d Log -T -v TESTLIBRARY:${serviceName}_library.robot \$i\$testname"; sleep 2; done || true

           popd
           """
      }
    }

    stage('Build/Install New Service') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail
           pushd $WORKSPACE/cord/orchestration/xos-services/${gerritProject}
           export DOCKER_TAG=\$(cat VERSION)-test
           export DOCKER_REPOSITORY=xosproject/
           export DOCKER_BUILD_ARGS=--no-cache
           echo "\$(cat VERSION)-test" > VERSION
           make docker-build

           #install newservice
           cd $WORKSPACE/cord/helm-charts
           helm upgrade --set image.tag=\$DOCKER_TAG \
                        --set image.pullPolicy=Never \
                         --recreate-pods ${serviceName} xos-services/${serviceName}
           popd
           """
      }
    }

    stage('Verify Service Upgrade') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -ex -o pipefail
           DOCKER_TAG=\$(cat $WORKSPACE/cord/orchestration/xos-services/${gerritProject}/VERSION)

           #wait for xos-core and models to be loaded
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"core\\").state'| grep -q present; do echo 'Waiting for Core to be loaded'; sleep 5; done"
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"${serviceName}\\").state'| grep -q present; do echo 'Waiting for New Service to be loaded'; sleep 5; done"
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"${serviceName}\\").version'| grep -q \$DOCKER_TAG; do echo 'Waiting for New Service Version Check'; sleep 5; done"
           sleep 120
           """
      }
    }

    stage('Test Post-Upgrade') {
      steps {
        sh """
           CORE_CONTAINER=\$(docker ps | grep k8s_xos-core | awk '{print \$1}')

           export testname=_service_api.robot
           export library=_library.robot
           SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
           echo \$SERVICES
           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests
           for i in \$SERVICES; do bash -c "robot -v SETUP_FLAG:Setup -i get -d Log -T -v TESTLIBRARY:${serviceName}_library.robot \$i\$testname"; sleep 2; done || true

           ## get pod logs
           for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
           do
             kubectl logs \$pod> $WORKSPACE/\$pod.log;
           done || true
           """
      }
    }

    /* Disable the downgrade step because the core doesn't support reverse migrations
    stage('Downgrade Service') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           pushd cord/helm-charts
           #delete service
           helm delete --purge ${gerritProject}

           #re-install service with previous version
           helm dep update xos-services/${gerritProject}
           helm install xos-services/${gerritProject} -n ${gerritProject}

           #wait for xos-core and models to be loaded
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"core\\").state'| grep -q present; do echo 'Waiting for Core to be loaded'; sleep 5; done"
           timeout 300 bash -c "until http -a admin@opencord.org:letmein GET http://127.0.0.1:30001/xosapi/v1/dynamicload/load_status | jq '.services[] | select(.name==\\"${gerritProject}\\").state'| grep -q present; do echo 'Waiting for Service to be loaded'; sleep 5; done"
           sleep 120
           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests

           CORE_CONTAINER=\$(docker ps | grep k8s_xos-core | awk '{print \$1}')

           export testname=_service_api.robot
           export library=_library.robot
           SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
           echo \$SERVICES
           for i in \$SERVICES; do bash -c "robot -v SETUP_FLAG:Setup -i get -d Log -T -v TESTLIBRARY:${gerritProject}_library.robot \$i\$testname"; sleep 2; done || true

           ## get pod logs
           for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
           do
             kubectl logs \$pod> $WORKSPACE/\$pod.log;
           done || true

           popd
           """
      }
    }
    */

  }

  post {
    always {
      sh """
         # copy robot logs
         if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
         cp -r $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/Log/*ml ./RobotLogs
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
         step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "kailash@opennetworking.org, scottb@opennetworking.org", sendToIndividuals: false])
    }
  }
}
