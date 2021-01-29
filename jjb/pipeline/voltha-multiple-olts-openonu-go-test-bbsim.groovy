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

           EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${params.extraHelmFlags} --set defaults.image_registry=mirror.registry.opennetworking.org/ "

           cd $WORKSPACE/kind-voltha/
           ./voltha up
           """
      }
    }

    stage('Run E2E Tests multi olts 1t1gem') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/multi-olts-1t1gem"
      }
      steps {
        sh '''
          # start logging
          mkdir -p $WORKSPACE/multi-olts-1t1gem
          _TAG=kail-multi-olts-1t1gem kail -n voltha -n default > $WORKSPACE/multi-olts-1t1gem/onos-voltha-combined.log &

          mkdir -p $ROBOT_LOGS_DIR/multi-olts-1t1gem
          export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
          export TARGET_DEFAULT=openonu-go-adapter-multi-olt-test
          export NAME=voltha_voltha

          make -C $WORKSPACE/voltha-system-tests \$TARGET_DEFAULT || true

          # stop logging
          P_IDS="$(ps e -ww -A | grep "_TAG=kail-multi-olts-1t1gem" | grep -v grep | awk '{print $1}')"
          if [ -n "$P_IDS" ]; then
            echo $P_IDS
            for P_ID in $P_IDS; do
              kill -9 $P_ID
            done
          fi

          # get pods information
          kubectl get pods -o wide --all-namespaces > $WORKSPACE/multi-olts-1t1gem/pods.txt || true
        '''
       }
     }

    stage('Run E2E Tests multi olts 1t4gem') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/multi-olts-1t4gem"
      }
      steps {
        sh '''
          cd $WORKSPACE/kind-voltha/
          #source $NAME-env.sh
          WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

          export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

          # start logging
          mkdir -p $WORKSPACE/multi-olts-1t4gem
          _TAG=kail-multi-olts-1t4gem kail -n voltha -n default > $WORKSPACE/multi-olts-1t4gem/onos-voltha-combined.log &

          DEPLOY_K8S=n ./voltha up

          mkdir -p $ROBOT_LOGS_DIR/multi-olts-1t4gem
          export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
          export TARGET_DEFAULT=1t4gem-openonu-go-adapter-multi-olt-test
          export NAME=voltha_voltha

          make -C $WORKSPACE/voltha-system-tests \$TARGET_DEFAULT || true

          # stop logging
          P_IDS="$(ps e -ww -A | grep "_TAG=kail-multi-olts-1t4gem" | grep -v grep | awk '{print $1}')"
          if [ -n "$P_IDS" ]; then
            echo $P_IDS
            for P_ID in $P_IDS; do
              kill -9 $P_ID
            done
          fi

          # get pods information
          kubectl get pods -o wide --all-namespaces > $WORKSPACE/multi-olts-1t4gem/pods.txt || true
        '''
       }
     }

    stage('Run E2E Tests multi olts 1t8gem') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/multi-olts-1t8gem"
      }
      steps {
        sh '''
          cd $WORKSPACE/kind-voltha/
          #source $NAME-env.sh
          WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

          export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

          # start logging
          mkdir -p $WORKSPACE/multi-olts-1t8gem
          _TAG=kail-multi-olts-1t8gem kail -n voltha -n default > $WORKSPACE/multi-olts-1t8gem/onos-voltha-combined.log &

          DEPLOY_K8S=n ./voltha up

          mkdir -p $ROBOT_LOGS_DIR/multi-olts-1t8gem
          export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
          export TARGET_1T8GEM=1t8gem-openonu-go-adapter-multi-olt-test
          export NAME=voltha_voltha

          make -C $WORKSPACE/voltha-system-tests \$TARGET_1T8GEM || true

          # stop logging
          P_IDS="$(ps e -ww -A | grep "_TAG=kail-multi-olts-1t8gem" | grep -v grep | awk '{print $1}')"
          if [ -n "$P_IDS" ]; then
            echo $P_IDS
            for P_ID in $P_IDS; do
              kill -9 $P_ID
            done
          fi

          # get pods information
          kubectl get pods -o wide --all-namespaces > $WORKSPACE/multi-olts-1t8gem/pods.txt || true
        '''
      }
    }
  }
  post {
    always {
      sh '''
         set +e
         # get pods information
         kubectl get pods -o wide
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}"
         helm ls

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

         extract_errors_go voltha-rw-core > $WORKSPACE/error-report.log || true
         extract_errors_go adapter-open-olt >> $WORKSPACE/error-report.log || true
         extract_errors_python adapter-open-onu >> $WORKSPACE/error-report.log || true
         extract_errors_python voltha-ofagent >> $WORKSPACE/error-report.log || true

         gzip $WORKSPACE/onos-voltha-combined.log || true
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
         archiveArtifacts artifacts: '**/*.log,**/*.gz,**/*.txt'
    }
  }
}
