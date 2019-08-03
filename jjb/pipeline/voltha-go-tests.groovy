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
      timeout(time: 40, unit: 'MINUTES')
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
           cd kind-voltha/
           TYPE=minimal WITH_RADIUS=y WITH_TP=no ./voltha up
           """
      }
    }

    stage('Install BBSIM') {
      steps {
        sh '''
           cd kind-voltha/
           export KUBECONFIG="$(./bin/kind get kubeconfig-path --name="voltha-minimal")"
           export VOLTCONFIG="/home/jenkins/.volt/config-minimal"
           export PATH=/w/workspace/kind-voltha-minimal/kind-voltha/bin:$PATH
           helm install --set onus_per_pon_port=1 onf/bbsim -n bbsim
           '''
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           git clone https://gerrit.opencord.org/voltha-system-tests
           cd kind-voltha/
           export KUBECONFIG="$(./bin/kind get kubeconfig-path --name="voltha-minimal")"
           export VOLTCONFIG="/home/jenkins/.volt/config-minimal"
           export PATH=/w/workspace/kind-voltha-minimal/kind-voltha/bin:$PATH
           cd $WORKSPACE/voltha-system-tests/tests/sanity
           robot -e notready -v num_onus:1 sanity.robot || true
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
         cd kind-voltha/
         cp install-minimal.log $WORKSPACE/
         export KUBECONFIG="$(./bin/kind get kubeconfig-path --name="voltha-minimal")"
         export VOLTCONFIG="/home/jenkins/.volt/config-minimal"
         export PATH=/w/workspace/kind-voltha-minimal/kind-voltha/bin:$PATH
         kubectl get pods --all-namespaces
         kubectl describe pods -n voltha
         voltctl || true
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
           if [[ \$pod == *"arouter"* ]]; then
             kubectl logs \$pod arouter -n voltha > $WORKSPACE/\$pod.log;
           else
             kubectl logs \$pod -n voltha > $WORKSPACE/\$pod.log;
           fi
         done
         '''
         archiveArtifacts artifacts: '*.log'

    }
  }
}
