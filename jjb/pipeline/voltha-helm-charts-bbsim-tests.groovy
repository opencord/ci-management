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
    label "${params.executorNode}"
  }
  options {
      timeout(time: 120, unit: 'MINUTES')
  }

  stages {

    stage('Repo') {
      steps {
        checkout(changelog: false, \
          poll: false,
          scm: [$class: 'RepoScm', \
            manifestRepositoryUrl: "${params.manifestUrl}", \
            manifestBranch: "${params.manifestBranch}", \
            currentBranch: true, \
            destinationDir: 'voltha-helm-charts', \
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
           cd $WORKSPACE/voltha-helm-charts
           PROJECT_PATH=\$(xmllint --xpath "string(//project[@name=\\\"${gerritProject}\\\"]/@path)" .repo/manifest.xml)
           repo download "\$PROJECT_PATH" "${gerritChangeNumber}/${gerritPatchsetNumber}"
           """
      }
    }
    stage('Create K8s Cluster') {
      steps {
        sh """
           cd $WORKSPACE
           git clone https://gerrit.opencord.org/voltha-system-tests
           git clone https://github.com/ciena/kind-voltha.git
           cd $WORKSPACE/kind-voltha/
           DEPLOY_K8S=y JUST_K8S=y FANCY=0 ./voltha up
           """
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh """
           cd $WORKSPACE/kind-voltha
           source ./minimal-env.sh

           export VOLTHA_CHART=$WORKSPACE/voltha-helm-charts/voltha
           export VOLTHA_ADAPTER_OPEN_OLT_CHART=$WORKSPACE/voltha-helm-charts/voltha-adapter-openolt
           export VOLTHA_ADAPTER_OPEN_ONU_CHART=$WORKSPACE/voltha-helm-charts/voltha-adapter-openonu
           helm dep update \$VOLTHA_CHART
           helm dep update \$VOLTHA_ADAPTER_OPEN_OLT_CHART
           helm dep update \$VOLTHA_ADAPTER_OPEN_ONU_CHART

           HELM_FLAG="--set defaults.image_tag=voltha-2.1 "
           echo \$HELM_FLAG
           EXTRA_HELM_FLAGS=\$HELM_FLAG VOLTHA_LOG_LEVEL=DEBUG TYPE=minimal WITH_RADIUS=y WITH_BBSIM=y INSTALL_ONOS_APPS=y CONFIG_SADIS=y FANCY=0 ./voltha up
           """
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           cd $WORKSPACE/kind-voltha/
           source ./minimal-env.sh
           cd $WORKSPACE/voltha-system-tests/tests/sanity
           make sanity-kind || true
           '''
      }
    }
  }

  post {
    always {
      sh '''
         # copy robot logs
         if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
         cp -r $WORKSPACE/voltha-system-tests/tests/sanity/*ml ./RobotLogs || true
         cd $WORKSPACE/kind-voltha/
         cp install-minimal.log $WORKSPACE/
	 source ./minimal-env.sh
         kubectl get pods --all-namespaces -o jsonpath="{..image}" |tr -s "[[:space:]]" "\n" | sort | uniq -c
         kubectl get nodes -o wide
         kubectl get pods -o wide
         kubectl get pods -n voltha -o wide
         ## get default pod logs
         for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
         do
           if [[ \$pod == *"onos"* && \$pod != *"onos-service"* ]]; then
             kubectl logs \$pod onos> $WORKSPACE/\$pod.log;
           else
             kubectl logs \$pod> $WORKSPACE/\$pod.log;
           fi
         done
         ## get voltha pod logs
         for pod in \$(kubectl get pods --no-headers -n voltha | awk '{print \$1}');
         do
           if [[ \$pod == *"-api-"* ]]; then
             kubectl logs \$pod arouter -n voltha > $WORKSPACE/\$pod.log;
           else
             kubectl logs \$pod -n voltha > $WORKSPACE/\$pod.log;
           fi
         done
         ## clean up node
	 WAIT_ON_DOWN=y ./voltha down
	 cd $WORKSPACE/
	 rm -rf kind-voltha/ voltha-system-tests/ voltha-helm-charts/ || true
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
         archiveArtifacts artifacts: '*.log'

    }
  }
}
