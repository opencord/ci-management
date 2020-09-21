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
    timeout(time: 90, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="$WORKSPACE/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    TYPE="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="n"
    WITH_RADIUS="y"
    WITH_BBSIM="y"
    DEPLOY_K8S="y"
    VOLTHA_LOG_LEVEL="DEBUG"
    CONFIG_SADIS="external"
    BBSIM_CFG="configs/bbsim-sadis-att.yaml"
    ROBOT_MISC_ARGS="-d $WORKSPACE/RobotLogs"
  }
  stages {
    stage('Clone kind-voltha') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/kind-voltha",
            refspec: "${kindVolthaChange}"
          ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "kind-voltha"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        sh """
        if [ '${kindVolthaChange}' != '' ] ; then
          cd $WORKSPACE/kind-voltha
          git fetch https://gerrit.opencord.org/kind-voltha ${kindVolthaChange} && git checkout FETCH_HEAD
        fi
        """
      }
    }
    stage('Clone voltha-system-tests') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/voltha-system-tests",
            refspec: "${volthaSystemTestsChange}"
          ]],
          branches: [[ name: "${branch}", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-system-tests"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        sh """
        if [ '${volthaSystemTestsChange}' != '' ] ; then
          cd $WORKSPACE/voltha-system-tests
          git fetch https://gerrit.opencord.org/voltha-system-tests ${volthaSystemTestsChange} && git checkout FETCH_HEAD
        fi
        """
      }
    }
    // If the repo under test is not kind-voltha
    // then download it and checkout the patch
    stage('Download Patch') {
      when {
        expression {
          return "${gerritProject}" != 'kind-voltha';
        }
      }
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/${gerritProject}",
            refspec: "${gerritRefspec}"
          ]],
          branches: [[ name: "${branch}", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "${gerritProject}"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        sh """
          pushd $WORKSPACE/${gerritProject}
          git fetch https://gerrit.opencord.org/${gerritProject} ${gerritRefspec} && git checkout FETCH_HEAD
          echo "Currently on commit: \n"
          git log -1 --oneline
          popd
          """
      }
    }
    // If the repo under test is kind-voltha we don't need to download it again,
    // as we already have it, simply checkout the patch
    stage('Checkout kind-voltha patch') {
      when {
        expression {
          return "${gerritProject}" == 'kind-voltha';
        }
      }
      steps {
        sh """
        cd $WORKSPACE/kind-voltha
        git fetch https://gerrit.opencord.org/kind-voltha ${gerritRefspec} && git checkout FETCH_HEAD
        """
      }
    }
    stage('Create K8s Cluster') {
      steps {
        sh """
           cd $WORKSPACE/kind-voltha/
           JUST_K8S=y ./voltha up
           bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/kind-voltha/bin"
           """
      }
    }

    stage('Build Images') {
      steps {
        sh """
          make -C $WORKSPACE/voltha-openonu-adapter-go DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
          """
      }
    }

    stage('Push Images') {
      steps {
        sh '''
           docker images | grep citest
           for image in \$(docker images -f "reference=*/*citest" --format "{{.Repository}}"); do echo "Pushing \$image to nodes"; kind load docker-image \$image:citest --name voltha-\$TYPE --nodes voltha-\$TYPE-worker,voltha-\$TYPE-worker2; done
           '''
      }
    }
    stage('Deploy Voltha') {
      steps {
        sh '''
           export EXTRA_HELM_FLAGS+="--set use_openonu_adapter_go=true,log_agent.enabled=False ${extraHelmFlags} "

           IMAGES="adapter_open_onu_go"

           for I in \$IMAGES
           do
             EXTRA_HELM_FLAGS+="--set images.\$I.tag=citest,images.\$I.pullPolicy=Never "
           done

           cd $WORKSPACE/kind-voltha/
           echo \$EXTRA_HELM_FLAGS
           kail -n voltha -n default > $WORKSPACE/onos-voltha-combined.log &
           ./voltha up
           '''
      }
    }

    stage('Run E2E Tests') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/openonu-go"
      }
      steps {
        sh '''
           mkdir -p $ROBOT_LOGS_DIR
           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
           export TARGET_DEFAULT=openonu-go-adapter-test

           make -C $WORKSPACE/voltha-system-tests \$TARGET_DEFAULT || true

           export TARGET_1T8GEM=1t8gem-openonu-go-adapter-test

           make -C $WORKSPACE/voltha-system-tests \$TARGET_1T8GEM || true
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

         sync
         pkill kail || true
         md5sum $WORKSPACE/kind-voltha/bin/voltctl

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


         ## shut down kind-voltha
         if [ "${branch}" != "master" ]; then
           echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
           source "$WORKSPACE/kind-voltha/releases/${branch}"
         else
           echo "on master, using default settings for kind-voltha"
         fi

         cd $WORKSPACE/kind-voltha
	       WAIT_ON_DOWN=y ./voltha down
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/*/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/*/output*.xml',
            outputPath: '.',
            passThreshold: 100,
            reportFileName: 'RobotLogs/*/report*.html',
            unstableThreshold: 0]);
         archiveArtifacts artifacts: '*.log,*.gz'
    }
  }
}
