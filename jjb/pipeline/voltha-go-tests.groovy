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
           git clone https://github.com/andybavier/kind-voltha.git
           """
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh """
           cd kind-voltha/
           EXTRA_HELM_FLAGS='--set images.rw_core.repository=karthick18/voltha-rw-core,images.rw_core.tag=2.1.0-dev-dirty-tp,images.rw_core.pullPolicy=Always' VOLTHA_LOG_LEVEL=DEBUG TYPE=minimal WITH_RADIUS=y WITH_TP=no WITH_BBSIM=y ./voltha up
           """
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           git clone https://gerrit.opencord.org/voltha-system-tests
           cd kind-voltha/
           export KUBECONFIG="$(./bin/kind get kubeconfig-path --name="voltha-minimal")"
           export VOLTCONFIG="/home/jenkins/.volt/config-minimal"
           export PATH=/w/workspace/voltha-go-e2e-tests/kind-voltha/bin:$PATH
           cd $WORKSPACE/voltha-system-tests/tests/sanity
           robot -e notready --critical sanity --noncritical VOL-1705 -v num_onus:1 sanity.robot || true
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
         export PATH=/w/workspace/voltha-go-e2e-tests/kind-voltha/bin:$PATH
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
