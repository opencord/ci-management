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
      timeout(time: 190, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="$PATH:$WORKSPACE/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    NAME="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="no"
    WITH_RADIUS="yes"
    WITH_BBSIM="yes"
    DEPLOY_K8S="yes"
    VOLTHA_LOG_LEVEL="DEBUG"
    CONFIG_SADIS="external"
    BBSIM_CFG="configs/bbsim-sadis-att.yaml"
    ROBOT_MISC_ARGS="-e PowerSwitch ${params.extraRobotArgs}"
    KARAF_HOME="${params.karafHome}"
    DIAGS_PROFILE="VOLTHA_PROFILE"
    NUM_OF_BBSIM="${olts}"
  }
  stages {
    stage('Clone kind-voltha') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/kind-voltha",
            // refspec: "${kindVolthaChange}"
          ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "kind-voltha"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
      }
    }
    stage('Cleanup') {
      steps {
        sh """
        cd $WORKSPACE/kind-voltha/
        WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down || ./voltha down
        """
      }
    }
    stage('Clone voltha-system-tests') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/voltha-system-tests",
            // refspec: "${volthaSystemTestsChange}"
          ]],
          branches: [[ name: "${branch}", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-system-tests"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh """
           export EXTRA_HELM_FLAGS=""
           if [ "${branch}" != "master" ]; then
             echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
             source "$WORKSPACE/kind-voltha/releases/${branch}"
           else
             echo "on master, using default settings for kind-voltha"
           fi

           EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${params.extraHelmFlags}"

           cd $WORKSPACE/kind-voltha/
           ./voltha up
           """
      }
    }

    stage('Device Management Interface Tests') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/DMITests"
      }
      steps {
        sh '''
           set +e
           mkdir -p $ROBOT_LOGS_DIR
           cd $WORKSPACE/kind-voltha/scripts
           ./log-collector.sh > /dev/null &
           ./log-combine.sh > /dev/null &

           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
           make -C $WORKSPACE/voltha-system-tests ${makeTarget} || true
           '''
      }
    }
  }

  post {
    always {
      sh '''
         set +e
         cp $WORKSPACE/kind-voltha/install-minimal.log $WORKSPACE/
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq
         kubectl get nodes -o wide
         kubectl get pods -o wide
         kubectl get pods -n voltha -o wide

         sleep 60 # Wait for log-collector and log-combine to complete

         ## Pull out errors from log files
         extract_errors_go() {
           echo
           echo "Error summary for $1:"
           grep '"level":"error"' $WORKSPACE/kind-voltha/scripts/logger/combined/$1*
           echo
         }

         extract_errors_python() {
           echo
           echo "Error summary for $1:"
           grep 'ERROR' $WORKSPACE/kind-voltha/scripts/logger/combined/$1*
           echo
         }

         extract_errors_go voltha-rw-core > $WORKSPACE/error-report.log
         extract_errors_go adapter-open-olt >> $WORKSPACE/error-report.log
         extract_errors_python adapter-open-onu >> $WORKSPACE/error-report.log
         extract_errors_go voltha-ofagent >> $WORKSPACE/error-report.log
         extract_errors_python onos >> $WORKSPACE/error-report.log

         gzip error-report.log || true

         cd $WORKSPACE/kind-voltha/scripts/logger/combined/
         tar czf $WORKSPACE/container-logs.tgz *

         cd $WORKSPACE
         gzip *-combined.log || true

         ## shut down voltha but leave kind-voltha cluster
         cd $WORKSPACE/kind-voltha/
         DEPLOY_K8S=n WAIT_ON_DOWN=y ./voltha down
         kubectl delete deployment voltctl || true
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: '**/log*.html',
            otherFiles: '',
            outputFileName: '**/output*.xml',
            outputPath: 'RobotLogs',
            passThreshold: 100,
            reportFileName: '**/report*.html',
            unstableThreshold: 0]);

         archiveArtifacts artifacts: '*.log,*.gz,*.tgz'

    }
  }
}
