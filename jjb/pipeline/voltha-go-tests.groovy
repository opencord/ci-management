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
      timeout(time: 40, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$WORKSPACE/kind-voltha/bin"
    TYPE="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="n"
    WITH_RADIUS="y"
    WITH_BBSIM="y"
    DEPLOY_K8S="y"
    VOLTHA_LOG_LEVEL="DEBUG"
    CONFIG_SADIS="n"
    EXTRA_HELM_FLAGS="${params.extraHelmFlags}"
    ROBOT_MISC_ARGS="${params.extraRobotArgs} -d $WORKSPACE/RobotLogs"
  }
  stages {

    stage('Repo') {
      steps {
        step([$class: 'WsCleanup'])
        checkout(changelog: true,
          poll: false,
          scm: [$class: 'RepoScm',
            manifestRepositoryUrl: "${params.manifestUrl}",
            manifestBranch: "${params.manifestBranch}",
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
           git clone https://github.com/ciena/kind-voltha.git
           """
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh """
           cd kind-voltha/
           ./voltha up
           """
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           set +e
           mkdir -p $WORKSPACE/RobotLogs
           git clone https://gerrit.opencord.org/voltha-system-tests
           cd $WORKSPACE/kind-voltha/scripts/
           ./log-collector.sh > $WORKSPACE/log-collector.log &
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
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
         kubectl get nodes -o wide
         kubectl get pods -o wide
         kubectl get pods -n voltha -o wide

         sleep 20
         pkill log-collector || true
         cd $WORKSPACE/kind-voltha/scripts/
         timeout 10 ./log-combine.sh > $WORKSPACE/log-combine.log || true
         cp ./logger/combined/* $WORKSPACE/
         for LOGFILE in $WORKSPACE/*.0001
         do
           NEWNAME=\${LOGFILE%.0001}
           mv \$LOGFILE \$NEWNAME
         done

         ## Pull out errors from log files
         extract_errors_go() {
           echo
           echo "*** BEGIN Errors in $1 logs ***"
           grep '"level":"error"' $WORKSPACE/$1* | cut -d ' ' -f 2- | jq '.'
           echo "***  END  Errors in $1 logs ***"
           echo
         }

         extract_errors_python() {
           echo
           echo "*** BEGIN Errors in $1 logs ***"
           grep 'ERROR' $WORKSPACE/$1*
           echo "***  END  Errors in $1 logs ***"
           echo
         }

         extract_errors_go voltha-rw-core > $WORKSPACE/error-report.log
         extract_errors_go adapter-open-olt >> $WORKSPACE/error-report.log
         extract_errors_python adapter-open-onu >> $WORKSPACE/error-report.log
         extract_errors_python voltha-ofagent >> $WORKSPACE/error-report.log

         ## shut down voltha
         cd $WORKSPACE/kind-voltha/
         WAIT_ON_DOWN=y ./voltha down
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
         archiveArtifacts artifacts: '*.log'

    }
  }
}
