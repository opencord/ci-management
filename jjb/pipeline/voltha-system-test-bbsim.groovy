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
    CONFIG_SADIS="y"
    EXTRA_HELM_FLAGS="${params.extraHelmFlags} --set voltha-etcd-cluster.clusterSize=3"
    ROBOT_MISC_ARGS="-d $WORKSPACE/RobotLogs"
  }
  stages {

    stage('Download kind-voltha') {
      steps {
        sh """
           git clone https://github.com/ciena/kind-voltha.git
           """
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh """
           pushd kind-voltha/
           ./voltha up
           popd
           """
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           rm -rf $WORKSPACE/RobotLogs; mkdir -p $WORKSPACE/RobotLogs
           git clone https://gerrit.opencord.org/voltha-system-tests
           make -C $WORKSPACE/voltha-system-tests ${makeTarget} || true
           '''
      }
    }

    //Remove this stage once https://jira.opencord.org/browse/VOL-1977 be resolved
    stage('Deploy Voltha Again') {
      steps {
        sh """
           pushd kind-voltha/
           ./voltha down
           ./voltha up
           popd
           """
      }
    }
    stage('Kubernetes Functional Tests') {
      steps {
        sh '''
           rm -rf $WORKSPACE/RobotLogs; mkdir -p $WORKSPACE/RobotLogs
           make -C $WORKSPACE/voltha-system-tests system-scale-test || true
           '''
      }
    }
  }

  post {
    always {
      sh '''
         set +e
         cd kind-voltha/
         cp install-minimal.log $WORKSPACE/
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
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
           elif [[ \$pod == "bbsim-"* ]]; then
             kubectl logs \$pod -n voltha -p > $WORKSPACE/\$pod.log;
           else
             kubectl logs \$pod -n voltha > $WORKSPACE/\$pod.log;
           fi
         done
         ## clean up node
         WAIT_ON_DOWN=y ./voltha down
         cd $WORKSPACE/
         rm -rf kind-voltha/ voltha-system-tests/ || true
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/*log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/*output*.xml',
            outputPath: '.',
            passThreshold: 100,
            reportFileName: 'RobotLogs/*report*.html',
            unstableThreshold: 0]);
         archiveArtifacts artifacts: '*.log'

    }
  }
}
