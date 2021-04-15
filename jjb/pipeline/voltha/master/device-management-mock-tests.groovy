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

// NOTE we are importing the library even if it's global so that it's
// easier to change the keywords during a replay
library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://gerrit.opencord.org/ci-management.git'
])

def localCharts = false

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: 90, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    ROBOT_MISC_ARGS="-d $WORKSPACE/RobotLogs"
  }

  stages {

    stage('Download Code') {
      steps {
        getVolthaCode([
          branch: "${branch}",
          gerritProject: "${gerritProject}",
          gerritRefspec: "${gerritRefspec}",
          // volthaSystemTestsChange: "${volthaSystemTestsChange}",
          // volthaHelmChartsChange: "${volthaHelmChartsChange}",
        ])
      }
    }
    stage('Build Redfish Importer Image') {
      steps {
        sh """
           make -C $WORKSPACE/device-management/\$1 DOCKER_REPOSITORY=opencord/ DOCKER_TAG=citest docker-build-importer
           """
      }
    }
    stage('Build demo_test Image') {
      steps {
        sh """
           make -C $WORKSPACE/device-management/\$1/demo_test DOCKER_REPOSITORY=opencord/ DOCKER_TAG=citest docker-build
           """
      }
    }
    stage('Build mock-redfish-server  Image') {
      steps {
        sh """
           make -C $WORKSPACE/device-management/\$1/mock-redfish-server DOCKER_REPOSITORY=opencord/ DOCKER_TAG=citest docker-build
           """
      }
    }
    stage('Create K8s Cluster') {
      steps {
        createKubernetesCluster([nodes: 3])
      }
    }
    stage('Load image in kind nodes') {
      steps {
        loadToKind()
      }
    }
    stage('Deploy Voltha') {
      steps {
        script {
          if (branch != "master" || volthaHelmChartsChange != "") {
            // if we're using a release or testing changes in the charts, then use the local clone
            localCharts = true
          }
        }
        volthaDeploy([
          workflow: "att",
          extraHelmFlags: extraHelmFlags,
          dockerRegistry: "mirror.registry.opennetworking.org",
          localCharts: localCharts,
        ])
        // start logging
        sh """
        mkdir -p $WORKSPACE/att
        _TAG=kail-att kail -n infra -n voltha > $WORKSPACE/att/onos-voltha-combined.log &
        """
        // forward ONOS and VOLTHA ports
        sh """
        _TAG=onos-port-forward kubectl port-forward --address 0.0.0.0 -n infra svc/voltha-infra-onos-classic-hs 8101:8101&
        _TAG=onos-port-forward kubectl port-forward --address 0.0.0.0 -n infra svc/voltha-infra-onos-classic-hs 8181:8181&
        _TAG=voltha-port-forward kubectl port-forward --address 0.0.0.0 -n voltha svc/voltha-voltha-api 55555:55555&
        """
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           mkdir -p $WORKSPACE/RobotLogs

           # tell the kubernetes script to use images tagged citest and pullPolicy:Never
           sed -i 's/master/citest/g' $WORKSPACE/device-management/kubernetes/deploy*.yaml
           sed -i 's/imagePullPolicy: Always/imagePullPolicy: Never/g' $WORKSPACE/device-management/kubernetes/deploy*.yaml
           make -C $WORKSPACE/device-management functional-mock-test || true
           '''
      }
    }
  }

  post {
    always {
      sh '''
         set +e
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq
         kubectl get nodes -o wide
         kubectl get pods -o wide
         kubectl get pods -n voltha -o wide

         sync
         pkill kail || true

         ## Pull out errors from log files
         extract_errors_go() {
           echo
           echo "Error summary for $1:"
           grep $1 $WORKSPACE/onos-voltha-combined.log | grep '"level":"error"' | cut -d ' ' -f 2- | jq -r '.msg'
           echo
         }

         extract_errors_python() {
           echo
           echo "Error summary for $1:"
           grep $1 $WORKSPACE/onos-voltha-combined.log | grep 'ERROR' | cut -d ' ' -f 2-
           echo
         }

         extract_errors_go voltha-rw-core > $WORKSPACE/error-report.log
         extract_errors_go adapter-open-olt >> $WORKSPACE/error-report.log
         extract_errors_python adapter-open-onu >> $WORKSPACE/error-report.log
         extract_errors_python voltha-ofagent >> $WORKSPACE/error-report.log

         gzip $WORKSPACE/onos-voltha-combined.log
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/output*.xml',
            outputPath: '.',
            passThreshold: 80,
            reportFileName: 'RobotLogs/report*.html',
            unstableThreshold: 0]);
         archiveArtifacts artifacts: '*.log,*.gz'
    }
  }
}
