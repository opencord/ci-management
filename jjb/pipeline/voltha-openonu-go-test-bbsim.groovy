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

    stage('Run E2E Tests 1t1gem') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/1t1gem"
      }
      steps {
        sh '''
          # start logging
          mkdir -p $WORKSPACE/1t1gem
          _TAG=kail-1t1gem kail -n voltha -n default > $WORKSPACE/1t1gem/onos-voltha-combined.log &

          mkdir -p $ROBOT_LOGS_DIR/1t1gem
          export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
          export TARGET_DEFAULT=openonu-go-adapter-test

          make -C $WORKSPACE/voltha-system-tests \$TARGET_DEFAULT || true

          # stop logging
          P_IDS="$(ps e -ww -A | grep "_TAG=kail-1t1gem" | grep -v grep | awk '{print $1}')"
          if [ -n "$P_IDS" ]; then
            echo $P_IDS
            for P_ID in $P_IDS; do
              kill -9 $P_ID
            done
          fi

          # get pods information
          kubectl get pods -o wide --all-namespaces > $WORKSPACE/1t1gem/pods.txt || true
        '''
       }
     }

    stage('Run E2E Tests 1t4gem') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/1t4gem"
      }
      steps {
        sh '''
          # start logging
          mkdir -p $WORKSPACE/1t4gem
          _TAG=kail-1t4gem kail -n voltha -n default > $WORKSPACE/1t4gem/onos-voltha-combined.log &

          mkdir -p $ROBOT_LOGS_DIR/1t4gem
          export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
          export TARGET_DEFAULT=1t4gem-openonu-go-adapter-test

          make -C $WORKSPACE/voltha-system-tests \$TARGET_DEFAULT || true

          # stop logging
          P_IDS="$(ps e -ww -A | grep "_TAG=kail-1t4gem" | grep -v grep | awk '{print $1}')"
          if [ -n "$P_IDS" ]; then
            echo $P_IDS
            for P_ID in $P_IDS; do
              kill -9 $P_ID
            done
          fi

          # get pods information
          kubectl get pods -o wide --all-namespaces > $WORKSPACE/1t4gem/pods.txt || true
        '''
       }
     }

    stage('Run MIB Upload Tests') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/openonu-go-MIB"
      }
      steps {
        sh '''
           cd $WORKSPACE/kind-voltha/
           #source $NAME-env.sh
           WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

           export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

           export EXTRA_HELM_FLAGS+="--set pon=2,onu=2,controlledActivation=only-onu "

           for I in \$IMAGES
           do
             EXTRA_HELM_FLAGS+="--set images.\$I.tag=citest,images.\$I.pullPolicy=Never "
           done

           # start logging
           mkdir -p $WORKSPACE/mib
           _TAG=kail-mib kail -n voltha -n default > $WORKSPACE/mib/onos-voltha-combined.log &

           DEPLOY_K8S=n ./voltha up

           mkdir -p $ROBOT_LOGS_DIR
           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
           export TARGET_DEFAULT=mib-upload-templating-openonu-go-adapter-test

           make -C $WORKSPACE/voltha-system-tests \$TARGET_DEFAULT || true

           # stop logging
           P_IDS="$(ps e -ww -A | grep "_TAG=kail-mib" | grep -v grep | awk '{print $1}')"
           if [ -n "$P_IDS" ]; then
             echo $P_IDS
             for P_ID in $P_IDS; do
               kill -9 $P_ID
             done
           fi

           # get pods information
           kubectl get pods -o wide --all-namespaces > $WORKSPACE/mib/pods.txt || true
        '''
      }
    }

    stage('Reconcile DT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ReconcileDT"
      }
      steps {
        sh '''
           cd $WORKSPACE/kind-voltha/
           #source $NAME-env.sh
           WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

           export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

           for I in \$IMAGES
           do
             EXTRA_HELM_FLAGS+="--set images.\$I.tag=citest,images.\$I.pullPolicy=Never "
           done

           # Workflow-specific flags
           export WITH_RADIUS=no
           export WITH_EAPOL=no
           export WITH_DHCP=no
           export WITH_IGMP=no
           export CONFIG_SADIS="external"
           export BBSIM_CFG="configs/bbsim-sadis-dt.yaml"

           # start logging
           mkdir -p $WORKSPACE/dt
           _TAG=kail-reconcile-dt kail -n voltha -n default > $WORKSPACE/reconciledt/onos-voltha-combined.log &

           DEPLOY_K8S=n ./voltha up

           mkdir -p $ROBOT_LOGS_DIR
           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR -e PowerSwitch"

           export TARGET=reconcile-openonu-go-adapter-test-dt


           make -C $WORKSPACE/voltha-system-tests \$TARGET || true

           # stop logging
           P_IDS="$(ps e -ww -A | grep "_TAG=kail-reconcile-dt" | grep -v grep | awk '{print $1}')"
           if [ -n "$P_IDS" ]; then
             echo $P_IDS
             for P_ID in $P_IDS; do
               kill -9 $P_ID
             done
           fi

           # get pods information
           kubectl get pods -o wide --all-namespaces > $WORKSPACE/reconciledt/pods.txt || true
           '''
      }
    }

    stage('Reconcile ATT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ReconcileATT"
      }
      steps {
        sh '''
           cd $WORKSPACE/kind-voltha/
           WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

           export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

           for I in \$IMAGES
           do
             EXTRA_HELM_FLAGS+="--set images.\$I.tag=citest,images.\$I.pullPolicy=Never "
           done

           # Workflow-specific flags
           export WITH_RADIUS=yes
           export WITH_EAPOL=yes
           export WITH_BBSIM=yes
           export DEPLOY_K8S=yes
           export CONFIG_SADIS="external"
           export BBSIM_CFG="configs/bbsim-sadis-att.yaml"

           if [ "${gerritProject}" = "voltctl" ]; then
             export VOLTCTL_VERSION=$(cat $WORKSPACE/voltctl/VERSION)
             cp $WORKSPACE/voltctl/voltctl $WORKSPACE/kind-voltha/bin/voltctl
             md5sum $WORKSPACE/kind-voltha/bin/voltctl
           fi

           # start logging
           mkdir -p $WORKSPACE/att
           _TAG=kail-reconcile-att kail -n voltha -n default > $WORKSPACE/reconcileatt/onos-voltha-combined.log &

           DEPLOY_K8S=n ./voltha up

           mkdir -p $ROBOT_LOGS_DIR
           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR -e PowerSwitch"

           export TARGET=reconcile-openonu-go-adapter-test


           make -C $WORKSPACE/voltha-system-tests \$TARGET || true

           # stop logging
           P_IDS="$(ps e -ww -A | grep "_TAG=kail-reconcile-att" | grep -v grep | awk '{print $1}')"
           if [ -n "$P_IDS" ]; then
             echo $P_IDS
             for P_ID in $P_IDS; do
               kill -9 $P_ID
             done
           fi

           # get pods information
           kubectl get pods -o wide --all-namespaces > $WORKSPACE/reconcileatt/pods.txt || true
           '''
      }
    }

    stage('Reconcile TT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ReconcileTT"
      }
      steps {
        sh '''
           cd $WORKSPACE/kind-voltha/
           WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

           export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

           for I in \$IMAGES
           do
             EXTRA_HELM_FLAGS+="--set images.\$I.tag=citest,images.\$I.pullPolicy=Never "
           done

           # Workflow-specific flags
           export WITH_RADIUS=no
           export WITH_EAPOL=no
           export WITH_DHCP=yes
           export WITH_IGMP=yes
           export CONFIG_SADIS="external"
           export BBSIM_CFG="configs/bbsim-sadis-tt.yaml"

           # start logging
           mkdir -p $WORKSPACE/tt
           _TAG=kail-reconcile-tt kail -n voltha -n default > $WORKSPACE/reconcilett/onos-voltha-combined.log &

           DEPLOY_K8S=n ./voltha up

           mkdir -p $ROBOT_LOGS_DIR
           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR -e PowerSwitch"

           export TARGET=reconcile-openonu-go-adapter-test-tt

           make -C $WORKSPACE/voltha-system-tests \$TARGET || true

           # stop logging
           P_IDS="$(ps e -ww -A | grep "_TAG=kail-reconcile-tt" | grep -v grep | awk '{print $1}')"
           if [ -n "$P_IDS" ]; then
             echo $P_IDS
             for P_ID in $P_IDS; do
               kill -9 $P_ID
             done
           fi

           # get pods information
           kubectl get pods -o wide --all-namespaces > $WORKSPACE/reconcilett/pods.txt || true
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
