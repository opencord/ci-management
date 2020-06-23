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
    PATH="$WORKSPACE/voltha/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    TYPE="minimal"
    VOLTHA_LOG_LEVEL="DEBUG"
    FANCY=0
    WITH_SIM_ADAPTERS="n"
  }

  stages {

    stage('Repo') {
      steps {
        step([$class: 'WsCleanup'])
        checkout(changelog: false, \
          poll: false,
          scm: [$class: 'RepoScm', \
            manifestRepositoryUrl: "${params.manifestUrl}", \
            manifestBranch: "${params.branch}", \
            currentBranch: true, \
            destinationDir: 'voltha', \
            forceSync: true,
            resetFirst: true, \
            quiet: true, \
            jobs: 4, \
            showAllChanges: true] \
          )
      }
    }
    stage('Patch') {
      steps {
        sh """
           pushd voltha
           if [ "${gerritProject}" != "" -a "${gerritChangeNumber}" != "" -a "${gerritPatchsetNumber}" != "" ]
           then
             repo download "${gerritProject}" "${gerritChangeNumber}/${gerritPatchsetNumber}"
           else
             echo "No patchset to download!"
           fi
           popd
           """
      }
    }
    stage('Create K8s Cluster') {
      steps {
        sh """
           if [ "${branch}" != "master" ]; then
             echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
             source "$WORKSPACE/voltha/kind-voltha/releases/${branch}"
           else
             echo "on master, using default settings for kind-voltha"
           fi

           cd $WORKSPACE/voltha/kind-voltha/
           JUST_K8S=y ./voltha up
           bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/voltha/kind-voltha/bin"
           """
      }
    }

    stage('Build Images') {
      steps {
        sh """
           make-local () {
             make -C $WORKSPACE/voltha/\$1 DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
           }
           if [ "${gerritProject}" = "pyvoltha" ]; then
             make -C $WORKSPACE/voltha/pyvoltha/ dist
             export LOCAL_PYVOLTHA=$WORKSPACE/voltha/pyvoltha/
             make-local voltha-openonu-adapter
           elif [ "${gerritProject}" = "voltha-lib-go" ]; then
             make -C $WORKSPACE/voltha/voltha-lib-go/ build
             export LOCAL_LIB_GO=$WORKSPACE/voltha/voltha-lib-go/
             make-local voltha-go
             make-local voltha-openolt-adapter
           elif [ "${gerritProject}" = "voltha-protos" ]; then
             make -C $WORKSPACE/voltha/voltha-protos/ build
             export LOCAL_PROTOS=$WORKSPACE/voltha/voltha-protos/
             make-local voltha-go
             make-local voltha-openolt-adapter
             make-local voltha-openonu-adapter
             make-local ofagent-py
           elif [ "${gerritProject}" = "voltctl" ]; then
             # Set and handle GOPATH and PATH
             export GOPATH=\${GOPATH:-$WORKSPACE/go}
             export PATH=\$PATH:/usr/lib/go-1.12/bin:/usr/local/go/bin:\$GOPATH/bin
             make -C $WORKSPACE/voltha/voltctl/ build
           elif ! [[ "${gerritProject}" =~ ^(voltha-helm-charts|voltha-system-tests|kind-voltha)\$ ]]; then
             make-local ${gerritProject}
           fi
           """
      }
    }

    stage('Push Images') {
      steps {
        sh '''
           if [ "${branch}" != "master" ]; then
             echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
             source "$WORKSPACE/voltha/kind-voltha/releases/${branch}"
           else
             echo "on master, using default settings for kind-voltha"
           fi

           if ! [[ "${gerritProject}" =~ ^(voltha-helm-charts|voltha-system-tests|voltctl|kind-voltha)\$ ]]; then
             export GOROOT=/usr/local/go
             export GOPATH=\$(pwd)
             docker images | grep citest
             for image in \$(docker images -f "reference=*/*citest" --format "{{.Repository}}"); do echo "Pushing \$image to nodes"; kind load docker-image \$image:citest --name voltha-\$TYPE --nodes voltha-\$TYPE-worker,voltha-\$TYPE-worker2; done
           fi
           '''
      }
    }

    stage('ATT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ATTWorkflow"
      }
      steps {
        sh '''
           if [ "${branch}" != "master" ]; then
             echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
             source "$WORKSPACE/voltha/kind-voltha/releases/${branch}"
           else
             echo "on master, using default settings for kind-voltha"
           fi

           # Workflow-specific flags
           export WITH_RADIUS=yes
           export WITH_BBSIM=yes
           export DEPLOY_K8S=yes
           export CONFIG_SADIS=no

           export EXTRA_HELM_FLAGS+="--set log_agent.enabled=False ${extraHelmFlags} "

           IMAGES=""
           if [ "${gerritProject}" = "voltha-go" ]; then
             IMAGES="rw_core ro_core "
           elif [ "${gerritProject}" = "ofagent-py" ]; then
             IMAGES="ofagent_py "
             EXTRA_HELM_FLAGS+="--set use_ofagent_go=false "
           elif [ "${gerritProject}" = "ofagent-go" ]; then
             IMAGES="ofagent_go "
           elif [ "${gerritProject}" = "voltha-onos" ]; then
             IMAGES="onos "
           elif [ "${gerritProject}" = "voltha-openolt-adapter" ]; then
             IMAGES="adapter_open_olt "
           elif [ "${gerritProject}" = "voltha-openonu-adapter" ]; then
             IMAGES="adapter_open_onu "
           elif [ "${gerritProject}" = "voltha-api-server" ]; then
             IMAGES="afrouter afrouterd "
           elif [ "${gerritProject}" = "bbsim" ]; then
             IMAGES="bbsim "
           elif [ "${gerritProject}" = "pyvoltha" ]; then
             IMAGES="adapter_open_onu "
           elif [ "${gerritProject}" = "voltha-lib-go" ]; then
             IMAGES="rw_core ro_core adapter_open_olt "
           elif [ "${gerritProject}" = "voltha-protos" ]; then
             IMAGES="rw_core ro_core adapter_open_olt adapter_open_onu ofagent "
           else
             echo "No images to push"
           fi

           for I in \$IMAGES
           do
             EXTRA_HELM_FLAGS+="--set images.\$I.tag=citest,images.\$I.pullPolicy=Never "
           done

           if [ "${gerritProject}" = "voltha-helm-charts" ]; then
             export CHART_PATH=$WORKSPACE/voltha/voltha-helm-charts
             export VOLTHA_CHART=\$CHART_PATH/voltha
             export VOLTHA_ADAPTER_OPEN_OLT_CHART=\$CHART_PATH/voltha-adapter-openolt
             export VOLTHA_ADAPTER_OPEN_ONU_CHART=\$CHART_PATH/voltha-adapter-openonu
             helm dep update \$VOLTHA_CHART
             helm dep update \$VOLTHA_ADAPTER_OPEN_OLT_CHART
             helm dep update \$VOLTHA_ADAPTER_OPEN_ONU_CHART
           fi

           if [ "${gerritProject}" = "voltctl" ]; then
             export VOLTCTL_VERSION=$(cat $WORKSPACE/voltha/voltctl/VERSION)
             cp $WORKSPACE/voltha/voltctl/voltctl $WORKSPACE/voltha/kind-voltha/bin/voltctl
             md5sum $WORKSPACE/voltha/kind-voltha/bin/voltctl
           fi

           printenv
           kail -n voltha -n default > $WORKSPACE/onos-voltha-combined.log &

           cd $WORKSPACE/voltha/kind-voltha/
           ./voltha up

           # minimal-env.sh contains the environment we used
           # Save value of EXTRA_HELM_FLAGS there to use in subsequent stages
           echo export EXTRA_HELM_FLAGS=\\"\$EXTRA_HELM_FLAGS\\" >> minimal-env.sh

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

           make -C $WORKSPACE/voltha/voltha-system-tests \$TARGET || true
           '''
      }
    }

    stage('DT workflow') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/DTWorkflow"
      }
      steps {
        sh '''
           cd $WORKSPACE/voltha/kind-voltha/
           source minimal-env.sh
           WAIT_ON_DOWN=y DEPLOY_K8S=n ./voltha down

           # Workflow-specific flags
           export WITH_RADIUS=no
           export WITH_EAPOL=no
           export WITH_DHCP=no
           export WITH_IGMP=no
           export CONFIG_SADIS=no

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

           make -C $WORKSPACE/voltha/voltha-system-tests \$TARGET || true
           '''
      }
    }
  }

  post {
    always {
      sh '''
         set +e
         cp $WORKSPACE/voltha/kind-voltha/install-minimal.log $WORKSPACE/
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq
         kubectl get nodes -o wide
         kubectl get pods -o wide
         kubectl get pods -n voltha -o wide

         sync
         pkill kail || true
         md5sum $WORKSPACE/voltha/kind-voltha/bin/voltctl

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
