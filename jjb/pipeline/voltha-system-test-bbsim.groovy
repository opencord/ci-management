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

// voltha-2.x e2e tests
// uses kind-voltha to deploy voltha-2.X
// uses bbsim to simulate OLT/ONUs


pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 80, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-full"
    VOLTCONFIG="$HOME/.volt/config-full"
    PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$WORKSPACE/kind-voltha/bin"
    TYPE="full"
    FANCY=0
    WITH_SIM_ADAPTERS="n"
    WITH_RADIUS="y"
    WITH_BBSIM="y"
    DEPLOY_K8S="y"
    VOLTHA_LOG_LEVEL="DEBUG"
    CONFIG_SADIS="y"
    EXTRA_HELM_FLAGS="${params.extraHelmFlags} --set voltha-etcd-cluster.clusterSize=3"
    ROBOT_MISC_ARGS="-d $WORKSPACE/RobotLogs"
  }

  stages {
    stage('Create Kubernetes Cluster') {
      steps {
        sh """
           git clone https://github.com/ciena/kind-voltha.git
           pushd kind-voltha/
           JUST_K8S=y ./voltha up
           popd
           """
      }
    }

    stage('Setup log collector') {
      steps {
        sh """
           bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/kind-voltha/bin"
           kail -n voltha -n default > $WORKSPACE/onos-voltha-combined.log &
           """
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh """
          if [ "${manifestBranch}" != "master" ]; then
             echo "on branch: ${manifestBranch}, sourcing kind-voltha/releases/${manifestBranch}"
             source "$HOME/kind-voltha/releases/${manifestBranch}"
           else
             echo "on master, using default settings for kind-voltha"
           fi

           pushd kind-voltha/
           ./voltha up
           popd
           """
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           rm -rf $WORKSPACE/RobotLogs; mkdir -p $WORKSPACE/RobotLogs
           git clone -b ${manifestBranch} https://gerrit.opencord.org/voltha-system-tests
           make ROBOT_DEBUG_LOG_OPT="-l sanity_log.html -r sanity_report.html -o sanity_output.xml" -C $WORKSPACE/voltha-system-tests ${makeTarget}
           '''
      }
    }

    stage('Kubernetes Functional Tests') {
      steps {
        sh '''
           make ROBOT_DEBUG_LOG_OPT="-l functional_log.html -r functional_report.html -o functional_output.xml" -C $WORKSPACE/voltha-system-tests system-scale-test
           '''
      }
    }

    stage('Kubernetes Failure Scenario Tests') {
      steps {
        sh '''
           make ROBOT_DEBUG_LOG_OPT="-l failure_log.html -r failure_report.html -o failure_output.xml"  -C $WORKSPACE/voltha-system-tests failure-test
           '''
      }
    }

  }

  post {
    always {
      sh '''
         set +e
         cp $WORKSPACE/kind-voltha/install-full.log $WORKSPACE/
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
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

         ## shut down kind-voltha
         cd $WORKSPACE/kind-voltha
	       WAIT_ON_DOWN=y ./voltha down

         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/*log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/*output*.xml',
            outputPath: '.',
            passThreshold: 100,
            reportFileName: 'RobotLogs/*report*.html',
            unstableThreshold: 0]);
         archiveArtifacts artifacts: '*.log'

    }
  }
}
