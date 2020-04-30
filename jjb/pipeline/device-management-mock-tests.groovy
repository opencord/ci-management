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
            manifestBranch: "${params.manifestBranch}", \
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
           repo download "${gerritProject}" "${gerritChangeNumber}/${gerritPatchsetNumber}"
           popd
           """
      }
    }
    stage('Create K8s Cluster') {
      steps {
        sh """
           git clone https://github.com/ciena/kind-voltha.git
           cd kind-voltha/
           JUST_K8S=y ./voltha up
           bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/kind-voltha/bin"
           """
      }
    }

    stage('Build Images') {
      steps {
        sh """
           make-local () {
             make -C $WORKSPACE/voltha/\$1 DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
           }
           """
      }
    }

    stage('Push Images') {
      steps {
        sh '''
             export GOROOT=/usr/local/go
             export GOPATH=\$(pwd)
             docker images | grep citest
             for image in \$(docker images -f "reference=*/*citest" --format "{{.Repository}}"); do echo "Pushing \$image to nodes"; kind load docker-image \$image:citest --name voltha-\$TYPE --nodes voltha-\$TYPE-worker,voltha-\$TYPE-worker2; done
           '''
      }
    }
    stage('Deploy Voltha') {
      steps {
        sh '''
           export EXTRA_HELM_FLAGS="--set log_agent.enabled=False ${extraHelmFlags} "

           cd $WORKSPACE/kind-voltha/
           echo \$EXTRA_HELM_FLAGS
           kail -n voltha -n default > $WORKSPACE/onos-voltha-combined.log &
           ./voltha up
           '''
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           mkdir -p $WORKSPACE/RobotLogs

           # tell the kubernetes script to use images tagged citest and pullPolicy:Never
           sed -i 's/master/citest/g' $WORKSPACE/voltha/device-management/kubernetes/deploy-redfish-importer.yaml
           sed -i 's/imagePullPolicy: Always/imagePullPolicy: Never/g' $WORKSPACE/voltha/device-management/kubernetes/deploy-redfish-importer.yaml
           make -C $WORKSPACE/voltha/device-management functional-mock-test || true
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

         sync
         pkill kail || true

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
         cd $WORKSPACE/kind-voltha
	       WAIT_ON_DOWN=y ./voltha down
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/output*.xml',
            outputPath: '.',
            passThreshold: 80,
            reportFileName: 'RobotLogs/report*.html',
            unstableThreshold: 0]);
         archiveArtifacts artifacts: '*.log,*.gz'
    }
  }
}
