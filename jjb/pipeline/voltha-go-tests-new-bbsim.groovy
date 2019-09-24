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
           rm -rf kind-voltha
           git clone https://github.com/ciena/kind-voltha.git
           """
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh """
           cd kind-voltha/
           EXTRA_HELM_FLAGS="${params.extraHelmFlags}" VOLTHA_LOG_LEVEL=DEBUG TYPE=minimal WITH_RADIUS=y WITH_BBSIM=n INSTALL_ONOS_APPS=y CONFIG_SADIS=y FANCY=0 ./voltha up
           """
      }
    }

    stage('Download BBSim repo') {
      steps {
        sh """
          cd $WORKSPACE
          rm -rf bbsim
          git clone https://github.com/opencord/bbsim.git bbsim
          """
      }
    }

    stage('config ONOS with BBSim sadis values') {
      steps {
        sh """
          cd $WORKSPACE/bbsim
          curl --user karaf:karaf -X POST --header 'Content-Type: application/json' --header 'Accept: application/json' -d @examples/sadis-minimal-voltha-2.json http://127.0.0.1:8181/onos/v1/network/configuration/apps
          curl --user karaf:karaf -X POST --header 'Content-Type: application/json' --header 'Accept: application/json' -d @examples/onos-dhcp.json  http://127.0.0.1:8181/onos/v1/network/configuration/apps
          """
      }
    }

    stage('Build and Deploy BBSim') {
      // This step doing multple things as it is (hopefully) a temporary step
      // once the new-bbsim is integrated with kind-voltha this all pipeline should be dismissed
      steps {
        sh '''
          cd $WORKSPACE/kind-voltha/
          export KUBECONFIG="$(./bin/kind get kubeconfig-path --name="voltha-minimal")"
          export VOLTCONFIG="/home/jenkins/.volt/config-minimal"
          export PATH=$WORKSPACE/kind-voltha/bin:$PATH
          cd $WORKSPACE/bbsim
          # DOCKER_TAG=candidate make docker-build
          # TYPE=minimal kind load docker-image voltha/bbsim:candidate --name voltha-\$TYPE --nodes voltha-\$TYPE-worker,voltha-\$TYPE-worker2;
          # helm install -n bbsim deployments/helm-chart/bbsim/ --set images.bbsim.tag=candidate --set images.bbsim.pullPolicy=IfNotProsent
          helm install -n bbsim deployments/helm-chart/bbsim/ --set images.bbsim.tag=master
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
           export PATH=$WORKSPACE/kind-voltha/bin:$PATH
           cd $WORKSPACE/voltha-system-tests/tests/sanity
           robot -v ONOS_REST_PORT:8181 -v ONOS_SSH_PORT:8101 -e notready --critical sanity -v num_onus:1 -v BBSIM_IP:bbsim-olt-id-0 -v BBSIM_OLT_SN:BBSIM_OLT_0 -b BBSIM_ONU_SN:BBSM00000001 sanity.robot || true
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
         export PATH=$WORKSPACE/kind-voltha/bin:$PATH
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
         rm -rf kind-voltha/ voltha-system-tests/ || true
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/output*.xml',
            outputPath: '.',
            passThreshold: 100,
            reportFileName: 'RobotLogs/report*.html',
            unstableThreshold: 0]);
         archiveArtifacts artifacts: '*.log'
         step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "teo@opennetworking.org", sendToIndividuals: false])
    }
  }
}
