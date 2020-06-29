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
      timeout(time: 60, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="$HOME/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    TYPE="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="no"
    WITH_RADIUS="yes"
    WITH_BBSIM="yes"
    DEPLOY_K8S="yes"
    VOLTHA_LOG_LEVEL="DEBUG"
    CONFIG_SADIS="no"
    ROBOT_MISC_ARGS="-e PowerSwitch ${params.extraRobotArgs}"
  }
  stages {

    stage('Repo') {
      steps {
        step([$class: 'WsCleanup'])
        checkout(changelog: true,
          poll: false,
          scm: [$class: 'RepoScm',
            manifestRepositoryUrl: "${params.manifestUrl}",
            manifestBranch: "${params.branch}",
            currentBranch: true,
            destinationDir: 'voltha',
            forceSync: true,
            resetFirst: true,
            quiet: true,
            jobs: 4,
            showAllChanges: true]
          )
      }
    }

    stage('Download kind-voltha') {
      steps {
        sh """
           cd $HOME
           [ -d kind-voltha ] || git clone https://gerrit.opencord.org/kind-voltha
           rm -rf $HOME/kind-voltha/scripts/logger
           cd $HOME/kind-voltha
           git pull
           """
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh """
           export EXTRA_HELM_FLAGS=""
           if [ "${branch}" != "master" ]; then
             echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
             source "$HOME/kind-voltha/releases/${branch}"
           else
             echo "on master, using default settings for kind-voltha"
           fi

           EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${params.extraHelmFlags} "

           cd $HOME/kind-voltha/
           WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down || ./voltha down
           ./voltha up
           """
      }
    }

    stage('Functional Tests') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/FunctionalTests"
      }
      steps {
        sh '''
           set +e
           mkdir -p $ROBOT_LOGS_DIR
           cd $HOME/kind-voltha/scripts
           ./log-collector.sh > /dev/null &
           ./log-combine.sh > /dev/null &

           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
           make -C $WORKSPACE/voltha/voltha-system-tests functional-single-kind || true
           '''
      }
    }

    stage('Alarm Tests') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/AlarmTests"
      }
      steps {
        sh '''
           set +e
           mkdir -p $WORKSPACE/RobotLogs

           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
           make -C $WORKSPACE/voltha/voltha-system-tests bbsim-alarms-kind || true
           '''
      }
    }

    stage('Failure/Recovery Tests') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/FailureTests"
      }
      steps {
        sh '''
           set +e
           mkdir -p $WORKSPACE/RobotLogs

           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
           make -C $WORKSPACE/voltha/voltha-system-tests bbsim-failurescenarios || true
           '''
      }
    }

    stage('Error Tests') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ErrorTests"
      }
      steps {
        sh '''
           set +e
           mkdir -p $WORKSPACE/RobotLogs

           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
           make -C $WORKSPACE/voltha/voltha-system-tests bbsim-errorscenarios || true
           '''
      }
    }
  }

  post {
    always {
      sh '''
         set +e
         cp $HOME/kind-voltha/install-minimal.log $WORKSPACE/
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
           grep '"level":"error"' $HOME/kind-voltha/scripts/logger/combined/$1*
           echo
         }

         extract_errors_python() {
           echo
           echo "Error summary for $1:"
           grep 'ERROR' $HOME/kind-voltha/scripts/logger/combined/$1*
           echo
         }

         extract_errors_go voltha-rw-core > $WORKSPACE/error-report.log
         extract_errors_go adapter-open-olt >> $WORKSPACE/error-report.log
         extract_errors_python adapter-open-onu >> $WORKSPACE/error-report.log
         extract_errors_go voltha-ofagent >> $WORKSPACE/error-report.log
         extract_errors_python onos >> $WORKSPACE/error-report.log

         cd $HOME/kind-voltha/scripts/logger/combined/
         tar czf $WORKSPACE/container-logs.tgz *

         cd $WORKSPACE
         gzip *-combined.log || true

         ## shut down voltha but leave kind-voltha cluster
         cd $HOME/kind-voltha/
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
