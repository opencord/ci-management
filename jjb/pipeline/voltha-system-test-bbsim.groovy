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


  def logKubernetes(prefix) {
      sh """
         set +e
         cd kind-voltha/
         cp install-minimal.log $WORKSPACE/${prefix}_instsall-minimal.log
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
         kubectl get nodes -o wide
         kubectl get pods -o wide
         kubectl get pods -n voltha -o wide
         ## get default pod logs
         for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
         do
           if [[ \$pod == *"onos"* && \$pod != *"onos-service"* ]]; then
             kubectl logs \$pod onos> $WORKSPACE/${prefix}_\$pod.log;
           else
             kubectl logs \$pod> $WORKSPACE/${prefix}_\$pod.log;
           fi
         done
         ## get voltha pod logs
         for pod in \$(kubectl get pods --no-headers -n voltha | awk '{print \$1}');
         do
           if [[ \$pod == *"-api-"* ]]; then
             kubectl logs \$pod arouter -n voltha > $WORKSPACE/${prefix}_\$pod.log;
           elif [[ \$pod == "bbsim-"* ]]; then
             kubectl logs \$pod -n voltha -p > $WORKSPACE/${prefix}_\$pod.log;
           else
             kubectl logs \$pod -n voltha > $WORKSPACE/${prefix}_\$pod.log;
           fi
         done
         """
  }


pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 80, unit: 'MINUTES')
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
           make ROBOT_DEBUG_LOG_OPT="-l sanity_log.html -r sanity_result.html -o sanity_result.xml" -C $WORKSPACE/voltha-system-tests ${makeTarget}
           '''
      }
    }

    stage('Log the kubernetes for sanity-test') {
      steps {
        logKubernetes('sanity_test')
      }
    }
    //Remove this stage once https://jira.opencord.org/browse/VOL-1977 be resolved
    stage('Deploy Voltha Again for Functional Tests') {
      steps {
        sh """
           pushd kind-voltha/
           WAIT_ON_DOWN=yes  DEPLOY_K8S=no ./voltha down
           DEPLOY_K8S=no ./voltha up
           popd
           """
      }
    }

    stage('Kubernetes Functional Tests') {
      steps {
        sh '''
           make ROBOT_DEBUG_LOG_OPT="-l functional_log.html -r functional_result.html -o functional_output.xml" -C $WORKSPACE/voltha-system-tests system-scale-test
           '''
      }
    }

    stage('Log the kubernetes for functional-test') {
      steps {
        logKubernetes('functional')
      }
    }

    //Remove this stage once https://jira.opencord.org/browse/VOL-1977 be resolved
    stage('Deploy Voltha Again for Failure Scenario Tests') {
      steps {
        sh """
           pushd kind-voltha/
           WAIT_ON_DOWN=yes  DEPLOY_K8S=no ./voltha down
           DEPLOY_K8S=no ./voltha up
           popd
           """
      }
    }

    stage('Kubernetes Failure Scenario Tests') {
      steps {
        sh '''
           make ROBOT_DEBUG_LOG_OPT="-l failure_log.html -r failure_result.html -o failure_output.xml"  -C $WORKSPACE/voltha-system-tests failure-test
           '''
      }
    }

    stage('Log the kubernetes for failure scenario test') {
      steps {
        logKubernetes('failure')
      }
    }

  }

  post {
    failure {
        logKubernetes('last')
    }
    aborted {
        logKubernetes('last')
    }
    cleanup {
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
