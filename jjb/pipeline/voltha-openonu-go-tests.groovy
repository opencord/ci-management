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
    NAME="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="n"
    WITH_RADIUS="y"
    WITH_BBSIM="y"
    DEPLOY_K8S="y"
    VOLTHA_LOG_LEVEL="DEBUG"
    CONFIG_SADIS="external"
    BBSIM_CFG="configs/bbsim-sadis-att.yaml"
    ROBOT_MISC_ARGS="-d $WORKSPACE/RobotLogs"
    EXTRA_HELM_FLAGS=" --set global.image_registry=mirror.registry.opennetworking.org/ "
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
          make -C $WORKSPACE/voltha-openonu-adapter-go DOCKER_REGISTRY=mirror.registry.opennetworking.org/ DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
          """
      }
    }

    stage('Push Images') {
      steps {
        sh '''
           docker images | grep citest
           for image in \$(docker images -f "reference=*/*/*citest" --format "{{.Repository}}"); do echo "Pushing \$image to nodes"; kind load docker-image \$image:citest --name voltha-\$NAME --nodes voltha-\$NAME-worker,voltha-\$NAME-worker2; done
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

           ./voltha up
           '''
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

    stage('Run E2E Tests 1t8gem') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/1t8gem"
      }
      steps {
        sh '''
          cd $WORKSPACE/kind-voltha/
          #source $NAME-env.sh
          WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

          export EXTRA_HELM_FLAGS+="--set use_openonu_adapter_go=true,log_agent.enabled=False ${extraHelmFlags} "

          IMAGES="adapter_open_onu_go"

          for I in \$IMAGES
          do
            EXTRA_HELM_FLAGS+="--set images.\$I.tag=citest,images.\$I.pullPolicy=Never "
          done
          #1t8gem
          mkdir -p $WORKSPACE/1t8gem
          _TAG=kail-1t8gem kail -n voltha -n default > $WORKSPACE/1t8gem/onos-voltha-combined.log &

          DEPLOY_K8S=n ./voltha up

          mkdir -p $ROBOT_LOGS_DIR/1t8gem
          export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
          export TARGET_1T8GEM=1t8gem-openonu-go-adapter-test

          make -C $WORKSPACE/voltha-system-tests \$TARGET_1T8GEM || true

          # stop logging
          P_IDS="$(ps e -ww -A | grep "_TAG=kail-1t8gem" | grep -v grep | awk '{print $1}')"
          if [ -n "$P_IDS" ]; then
            echo $P_IDS
            for P_ID in $P_IDS; do
              kill -9 $P_ID
            done
          fi

          # get pods information
          kubectl get pods -o wide --all-namespaces > $WORKSPACE/1t8gem/pods.txt || true
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

           export EXTRA_HELM_FLAGS+="--set use_openonu_adapter_go=true,log_agent.enabled=False ${extraHelmFlags} "

           export EXTRA_HELM_FLAGS+="--set pon=2,onu=2,controlledActivation=only-onu "

           IMAGES="adapter_open_onu_go"

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

    stage('DT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/DTWorkflow"
      }
      steps {
        sh '''
           cd $WORKSPACE/kind-voltha/
           #source $NAME-env.sh
           WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

           export EXTRA_HELM_FLAGS+="--set use_openonu_adapter_go=true,log_agent.enabled=False ${extraHelmFlags} "

           IMAGES="adapter_open_onu_go"

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
           _TAG=kail-dt kail -n voltha -n default > $WORKSPACE/dt/onos-voltha-combined.log &

           DEPLOY_K8S=n ./voltha up

           mkdir -p $ROBOT_LOGS_DIR
           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR -e PowerSwitch"

           # By default, all tests tagged 'sanityDt' are run.  This covers basic functionality
           # like running through the DT workflow for a single subscriber.
           export TARGET=sanity-kind-dt

           # If the Gerrit comment contains a line with "functional tests" then run the full
           # functional test suite.  This covers tests tagged either 'sanityDt' or 'functionalDt'.
           # Note: Gerrit comment text will be prefixed by "Patch set n:" and a blank line
           REGEX="functional tests"
           if [[ "$GERRIT_EVENT_COMMENT_TEXT" =~ \$REGEX ]]; then
             TARGET=functional-single-kind-dt
           fi

           make -C $WORKSPACE/voltha-system-tests \$TARGET || true

           # stop logging
           P_IDS="$(ps e -ww -A | grep "_TAG=kail-dt" | grep -v grep | awk '{print $1}')"
           if [ -n "$P_IDS" ]; then
             echo $P_IDS
             for P_ID in $P_IDS; do
               kill -9 $P_ID
             done
           fi

           # get pods information
           kubectl get pods -o wide --all-namespaces > $WORKSPACE/dt/pods.txt || true
           '''
      }
    }

    stage('ATT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ATTWorkflow"
      }
      steps {
        sh '''
           cd $WORKSPACE/kind-voltha/
           WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

           export EXTRA_HELM_FLAGS+="--set use_openonu_adapter_go=true,log_agent.enabled=False ${extraHelmFlags} "

           IMAGES="adapter_open_onu_go"

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
           _TAG=kail-att kail -n voltha -n default > $WORKSPACE/att/onos-voltha-combined.log &

           DEPLOY_K8S=n ./voltha up

           mkdir -p $ROBOT_LOGS_DIR
           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR -e PowerSwitch"

           # By default, all tests tagged 'sanity' are run.  This covers basic functionality
           # like running through the ATT workflow for a single subscriber.
           export TARGET=sanity-single-kind

           # If the Gerrit comment contains a line with "functional tests" then run the full
           # functional test suite.  This covers tests tagged either 'sanity' or 'functional'.
           # Note: Gerrit comment text will be prefixed by "Patch set n:" and a blank line
           REGEX="functional tests"
           if [[ "$GERRIT_EVENT_COMMENT_TEXT" =~ \$REGEX ]]; then
             TARGET=functional-single-kind
           fi

           make -C $WORKSPACE/voltha-system-tests \$TARGET || true

           # stop logging
           P_IDS="$(ps e -ww -A | grep "_TAG=kail-att" | grep -v grep | awk '{print $1}')"
           if [ -n "$P_IDS" ]; then
             echo $P_IDS
             for P_ID in $P_IDS; do
               kill -9 $P_ID
             done
           fi

           # get pods information
           kubectl get pods -o wide --all-namespaces > $WORKSPACE/att/pods.txt || true
           '''
      }
    }

    stage('TT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/TTWorkflow"
      }
      steps {
        sh '''
           cd $WORKSPACE/kind-voltha/
           WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

           export EXTRA_HELM_FLAGS+="--set use_openonu_adapter_go=true,log_agent.enabled=False ${extraHelmFlags} "

           IMAGES="adapter_open_onu_go"

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
           _TAG=kail-tt kail -n voltha -n default > $WORKSPACE/tt/onos-voltha-combined.log &

           DEPLOY_K8S=n ./voltha up

           mkdir -p $ROBOT_LOGS_DIR
           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR -e PowerSwitch"

           # By default, all tests tagged 'sanityTt' are run.  This covers basic functionality
           # like running through the TT workflow for a single subscriber.
           export TARGET=sanity-kind-tt

           # If the Gerrit comment contains a line with "functional tests" then run the full
           # functional test suite.  This covers tests tagged either 'sanityTt' or 'functionalTt'.
           # Note: Gerrit comment text will be prefixed by "Patch set n:" and a blank line
           REGEX="functional tests"
           if [[ "$GERRIT_EVENT_COMMENT_TEXT" =~ \$REGEX ]]; then
             TARGET=functional-single-kind-tt
           fi

           make -C $WORKSPACE/voltha-system-tests \$TARGET || true

           # stop logging
           P_IDS="$(ps e -ww -A | grep "_TAG=kail-tt" | grep -v grep | awk '{print $1}')"
           if [ -n "$P_IDS" ]; then
             echo $P_IDS
             for P_ID in $P_IDS; do
               kill -9 $P_ID
             done
           fi

           # get pods information
           kubectl get pods -o wide --all-namespaces > $WORKSPACE/tt/pods.txt || true
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
