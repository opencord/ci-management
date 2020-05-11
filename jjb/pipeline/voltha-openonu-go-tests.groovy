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
    FANCY=0
    WITH_SIM_ADAPTERS="n"
    WITH_RADIUS="y"
    WITH_BBSIM="y"
    DEPLOY_K8S="y"
    VOLTHA_LOG_LEVEL="DEBUG"
    CONFIG_SADIS="n"
    ROBOT_MISC_ARGS="-d $WORKSPACE/RobotLogs"
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
          if [ "${gerritProject}" != "voltha-openonu-adapter-go"]
          then
            echo "This pipeline is reserved for 'voltha-openonu-adapter-go' you are probably looking for 'voltha-bbsim-test.groovy'"
            exit 1
          fi
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
           cd $WORKSPACE/voltha/kind-voltha/
           JUST_K8S=y ./voltha up
           bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/voltha/kind-voltha/bin"
           """
      }
    }

    stage('Build Images') {
      steps {
        sh """
          make -C $WORKSPACE/voltha/voltha-openonu-adapter-go DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
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

           cd $WORKSPACE/voltha/kind-voltha/
           echo \$EXTRA_HELM_FLAGS
           kail -n voltha -n default > $WORKSPACE/onos-voltha-combined.log &
           ./voltha up
           '''
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           mkdir -p $ROBOT_LOGS_DIR
           export ROBOT_MISC_ARGS="-d $ROBOT_LOGS_DIR"
           export TARGET=openonu-go-adapter-test

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


         ## shut down kind-voltha
         if [ "${branch}" != "master" ]; then
           echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
           source "$WORKSPACE/voltha/kind-voltha/releases/${branch}"
         else
           echo "on master, using default settings for kind-voltha"
         fi

         cd $WORKSPACE/voltha/kind-voltha
	       WAIT_ON_DOWN=y ./voltha down
         '''
         archiveArtifacts artifacts: '*.log,*.gz'
    }
  }
}
