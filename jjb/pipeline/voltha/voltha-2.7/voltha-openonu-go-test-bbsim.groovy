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
    timeout(time: 130, unit: 'MINUTES')
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
        timeout(time: 10, unit: 'MINUTES') {
          sh """
          if [ "${branch}" != "master" ]; then
            echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
            source "$WORKSPACE/kind-voltha/releases/${branch}"
          else
            echo "on master, using default settings for kind-voltha"
          fi
          cd $WORKSPACE/kind-voltha/
          WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down || ./voltha down
          """
        }
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
        timeout(time: 10, unit: 'MINUTES') {
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
             bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/kind-voltha/bin"
             """
         }
      }
    }

    stage('Run E2E Tests 1t1gem') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/1t1gem"
      }
      steps {
        timeout(time: 10, unit: 'MINUTES') {
          sh '''
            # start logging
            mkdir -p $WORKSPACE/1t1gem
            _TAG=kail-1t1gem kail -n voltha -n default > $WORKSPACE/1t1gem/onos-voltha-combined.log &

            mkdir -p $ROBOT_LOGS_DIR/1t1gem
            export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
            export KVSTOREPREFIX=voltha_voltha

            make -C $WORKSPACE/voltha-system-tests ${makeTarget} || true

            # stop logging
            P_IDS="$(ps e -ww -A | grep "_TAG=kail-1t1gem" | grep -v grep | awk '{print $1}')"
            if [ -n "$P_IDS" ]; then
              echo $P_IDS
              for P_ID in $P_IDS; do
                kill -9 $P_ID
              done
            fi
            cd $WORKSPACE/1t1gem/
            gzip onos-voltha-combined.log
            rm onos-voltha-combined.log
            # get pods information
            kubectl get pods -o wide --all-namespaces > $WORKSPACE/1t1gem/pods.txt || true
          '''
         }
       }
     }

    stage('Run E2E Tests 1t4gem') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/1t4gem"
      }
      steps {
        timeout(time: 10, unit: 'MINUTES') {
          sh '''
            if [ "${branch}" != "master" ]; then
              echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
              source "$WORKSPACE/kind-voltha/releases/${branch}"
            else
              echo "on master, using default settings for kind-voltha"
            fi
            cd $WORKSPACE/kind-voltha/
            WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

            export EXTRA_HELM_FLAGS=""
            if [ "${branch}" != "master" ]; then
              echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
              source "$WORKSPACE/kind-voltha/releases/${branch}"
            else
              echo "on master, using default settings for kind-voltha"
            fi
            export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

            # start logging
            mkdir -p $WORKSPACE/1t4gem
            _TAG=kail-1t4gem kail -n voltha -n default > $WORKSPACE/1t4gem/onos-voltha-combined.log &

            DEPLOY_K8S=n ./voltha up

            mkdir -p $ROBOT_LOGS_DIR/1t4gem
            export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
            export KVSTOREPREFIX=voltha_voltha

            make -C $WORKSPACE/voltha-system-tests ${make1t4gemTestTarget} || true

            # stop logging
            P_IDS="$(ps e -ww -A | grep "_TAG=kail-1t4gem" | grep -v grep | awk '{print $1}')"
            if [ -n "$P_IDS" ]; then
              echo $P_IDS
              for P_ID in $P_IDS; do
                kill -9 $P_ID
              done
            fi
            cd $WORKSPACE/1t4gem/
            gzip onos-voltha-combined.log
            rm onos-voltha-combined.log
            # get pods information
            kubectl get pods -o wide --all-namespaces > $WORKSPACE/1t4gem/pods.txt || true
          '''
         }
       }
     }

    stage('Run E2E Tests 1t8gem') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/1t8gem"
      }
      steps {
        timeout(time: 10, unit: 'MINUTES') {
          sh '''
            if [ "${branch}" != "master" ]; then
              echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
              source "$WORKSPACE/kind-voltha/releases/${branch}"
            else
              echo "on master, using default settings for kind-voltha"
            fi
            cd $WORKSPACE/kind-voltha/
            WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

            export EXTRA_HELM_FLAGS=""
            if [ "${branch}" != "master" ]; then
              echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
              source "$WORKSPACE/kind-voltha/releases/${branch}"
            else
              echo "on master, using default settings for kind-voltha"
            fi
            export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

            # start logging
            mkdir -p $WORKSPACE/1t8gem
            _TAG=kail-1t8gem kail -n voltha -n default > $WORKSPACE/1t8gem/onos-voltha-combined.log &

            DEPLOY_K8S=n ./voltha up

            mkdir -p $ROBOT_LOGS_DIR/1t8gem
            export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
            export KVSTOREPREFIX=voltha_voltha

            make -C $WORKSPACE/voltha-system-tests ${make1t8gemTestTarget} || true

            # stop logging
            P_IDS="$(ps e -ww -A | grep "_TAG=kail-1t8gem" | grep -v grep | awk '{print $1}')"
            if [ -n "$P_IDS" ]; then
              echo $P_IDS
              for P_ID in $P_IDS; do
                kill -9 $P_ID
              done
            fi
            cd $WORKSPACE/1t8gem/
            gzip onos-voltha-combined.log
            rm onos-voltha-combined.log
            # get pods information
            kubectl get pods -o wide --all-namespaces > $WORKSPACE/1t8gem/pods.txt || true
          '''
        }
      }
    }

    stage('Run MIB Upload Tests') {
      when { beforeAgent true; expression { return "${olts}" == "1" } }
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/openonu-go-MIB"
      }
      steps {
        timeout(time: 10, unit: 'MINUTES') {
          sh '''
             if [ "${branch}" != "master" ]; then
               echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
               source "$WORKSPACE/kind-voltha/releases/${branch}"
             else
               echo "on master, using default settings for kind-voltha"
             fi
             cd $WORKSPACE/kind-voltha/
             WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

             export EXTRA_HELM_FLAGS=""
             if [ "${branch}" != "master" ]; then
               echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
               source "$WORKSPACE/kind-voltha/releases/${branch}"
             else
               echo "on master, using default settings for kind-voltha"
             fi
             export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "
             export EXTRA_HELM_FLAGS+="--set pon=2,onu=2,controlledActivation=only-onu "

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
             cd $WORKSPACE/mib/
             gzip onos-voltha-combined.log
             rm onos-voltha-combined.log
             # get pods information
             kubectl get pods -o wide --all-namespaces > $WORKSPACE/mib/pods.txt || true
          '''
        }
      }
    }

    stage('Reconcile DT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ReconcileDT"
      }
      steps {
        timeout(time: 20, unit: 'MINUTES') {
          sh '''
             if [ "${branch}" != "master" ]; then
               echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
               source "$WORKSPACE/kind-voltha/releases/${branch}"
             else
               echo "on master, using default settings for kind-voltha"
             fi
             cd $WORKSPACE/kind-voltha/
             WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

             export EXTRA_HELM_FLAGS=""
             if [ "${branch}" != "master" ]; then
               echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
               source "$WORKSPACE/kind-voltha/releases/${branch}"
             else
               echo "on master, using default settings for kind-voltha"
             fi
             export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

             # Workflow-specific flags
             export WITH_RADIUS=no
             export WITH_EAPOL=no
             export WITH_DHCP=no
             export WITH_IGMP=no
             export CONFIG_SADIS="external"
             export BBSIM_CFG="configs/bbsim-sadis-dt.yaml"

             # start logging
             mkdir -p $WORKSPACE/reconciledt
             _TAG=kail-reconcile-dt kail -n voltha -n default > $WORKSPACE/reconciledt/onos-voltha-combined.log &

             DEPLOY_K8S=n ./voltha up

             mkdir -p $ROBOT_LOGS_DIR
             export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR -e PowerSwitch"

             make -C $WORKSPACE/voltha-system-tests ${makeReconcileDtTestTarget} || true

             # stop logging
             P_IDS="$(ps e -ww -A | grep "_TAG=kail-reconcile-dt" | grep -v grep | awk '{print $1}')"
             if [ -n "$P_IDS" ]; then
               echo $P_IDS
               for P_ID in $P_IDS; do
                 kill -9 $P_ID
               done
             fi
             cd $WORKSPACE/reconciledt/
             gzip onos-voltha-combined.log
             rm onos-voltha-combined.log
             # get pods information
             kubectl get pods -o wide --all-namespaces > $WORKSPACE/reconciledt/pods.txt || true
             '''
         }
      }
    }

    stage('Reconcile ATT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ReconcileATT"
      }
      steps {
        timeout(time: 20, unit: 'MINUTES') {
          sh '''
             if [ "${branch}" != "master" ]; then
               echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
               source "$WORKSPACE/kind-voltha/releases/${branch}"
             else
               echo "on master, using default settings for kind-voltha"
             fi
             cd $WORKSPACE/kind-voltha/
             WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

             export EXTRA_HELM_FLAGS=""
             if [ "${branch}" != "master" ]; then
               echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
               source "$WORKSPACE/kind-voltha/releases/${branch}"
             else
               echo "on master, using default settings for kind-voltha"
             fi
             export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

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
             mkdir -p $WORKSPACE/reconcileatt
             _TAG=kail-reconcile-att kail -n voltha -n default > $WORKSPACE/reconcileatt/onos-voltha-combined.log &

             DEPLOY_K8S=n ./voltha up

             mkdir -p $ROBOT_LOGS_DIR
             export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR -e PowerSwitch"

             make -C $WORKSPACE/voltha-system-tests ${makeReconcileTestTarget} || true

             # stop logging
             P_IDS="$(ps e -ww -A | grep "_TAG=kail-reconcile-att" | grep -v grep | awk '{print $1}')"
             if [ -n "$P_IDS" ]; then
               echo $P_IDS
               for P_ID in $P_IDS; do
                 kill -9 $P_ID
               done
             fi
             cd $WORKSPACE/reconcileatt/
             gzip onos-voltha-combined.log
             rm onos-voltha-combined.log
             # get pods information
             kubectl get pods -o wide --all-namespaces > $WORKSPACE/reconcileatt/pods.txt || true
             '''
         }
      }
    }

    stage('Reconcile TT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ReconcileTT"
      }
      steps {
        timeout(time: 20, unit: 'MINUTES') {
          sh '''
             if [ "${branch}" != "master" ]; then
               echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
               source "$WORKSPACE/kind-voltha/releases/${branch}"
             else
               echo "on master, using default settings for kind-voltha"
             fi
             cd $WORKSPACE/kind-voltha/
             WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

             export EXTRA_HELM_FLAGS=""
             if [ "${branch}" != "master" ]; then
               echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
               source "$WORKSPACE/kind-voltha/releases/${branch}"
             else
               echo "on master, using default settings for kind-voltha"
             fi
             export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

             # Workflow-specific flags
             export WITH_RADIUS=no
             export WITH_EAPOL=no
             export WITH_DHCP=yes
             export WITH_IGMP=yes
             export CONFIG_SADIS="external"
             export BBSIM_CFG="configs/bbsim-sadis-tt.yaml"

             # start logging
             mkdir -p $WORKSPACE/reconcilett
             _TAG=kail-reconcile-tt kail -n voltha -n default > $WORKSPACE/reconcilett/onos-voltha-combined.log &

             DEPLOY_K8S=n ./voltha up

             mkdir -p $ROBOT_LOGS_DIR
             export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR -e PowerSwitch"

             make -C $WORKSPACE/voltha-system-tests ${makeReconcileTtTestTarget} || true

             # stop logging
             P_IDS="$(ps e -ww -A | grep "_TAG=kail-reconcile-tt" | grep -v grep | awk '{print $1}')"
             if [ -n "$P_IDS" ]; then
               echo $P_IDS
               for P_ID in $P_IDS; do
                 kill -9 $P_ID
               done
             fi
             cd $WORKSPACE/reconcilett/
             gzip onos-voltha-combined.log
             rm onos-voltha-combined.log
             # get pods information
             kubectl get pods -o wide --all-namespaces > $WORKSPACE/reconcilett/pods.txt || true
             '''
           }
      }
    }
  }
  post {
    always {
      sh '''
         # get pods information
         kubectl get pods -o wide
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}"
         helm ls

         sync
         pkill kail || true
         md5sum $WORKSPACE/kind-voltha/bin/voltctl

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
