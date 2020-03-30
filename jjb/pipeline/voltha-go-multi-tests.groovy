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
           cd $WORKSPACE
           [ -d kind-voltha ] || git clone https://github.com/ciena/kind-voltha.git
           rm -rf $WORKSPACE/kind-voltha/scripts/logger
           cd $WORKSPACE/kind-voltha
           git pull
           """
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh """
           export EXTRA_HELM_FLAGS=""
           if [ "${manifestBranch}" != "master" ]; then
             echo "on branch: ${manifestBranch}, sourcing kind-voltha/releases/${manifestBranch}"
             source "$WORKSPACE/kind-voltha/releases/${manifestBranch}"
           else
             echo "on master, using default settings for kind-voltha"
           fi

           EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${params.extraHelmFlags} "

           cd $WORKSPACE/kind-voltha/
           WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down || ./voltha down
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

           cd $WORKSPACE/kind-voltha/scripts
           ./log-collector.sh > /dev/null &
           ./log-combine.sh > /dev/null &

           for i in \$(seq 1 ${testRuns})
           do
             make -C $WORKSPACE/voltha-system-tests ${makeTarget}
             echo "Completed run: \$i"
             echo ""
           done
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

         cd $WORkSPACE/kind-voltha/scripts/logger/combined/
         tar czf $WORKSPACE/container-logs.tgz *

         cd $WORKSPACE
         gzip *-combined.log || true

         ## shut down voltha but leave kind-voltha cluster
         cd $WORKSPACE/kind-voltha/
         DEPLOY_K8S=n WAIT_ON_DOWN=y ./voltha down
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
         archiveArtifacts artifacts: '*.log,*.gz,*.tgz'

    }
  }
}
