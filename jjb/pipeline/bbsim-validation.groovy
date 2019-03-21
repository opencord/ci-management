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

// chart-api-test-helm.groovy
// Checks functionality of the helm-chart, without overriding the version/tag used

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  stages {

    stage('repo') {
      steps {
        checkout(changelog: false, \
          poll: false,
          scm: [$class: 'RepoScm', \
            manifestRepositoryUrl: "${params.manifestUrl}", \
            manifestBranch: "${params.manifestBranch}", \
            currentBranch: true, \
            destinationDir: 'cord', \
            forceSync: true,
            resetFirst: true, \
            quiet: true, \
            jobs: 4, \
            showAllChanges: true] \
          )
      }
    }

    stage('Clean up') {
      steps {
            sh """
            rm -rf voltha-bbsim/
            rm -rf pod-configs/
            rm -rf cord/helm-charts/helm-repo-tools/
            for hchart in \$(helm list -q | grep -E -v 'docker-registry|mavenrepo|ponnet');
            do
                echo "Purging chart: \${hchart}"
                helm delete --purge "\${hchart}"
            done
            """
      }
    }

    stage('Build BBSIM') {
      steps {
        sh '''
           git clone https://github.com/opencord/voltha-bbsim
           cd voltha-bbsim/
           docker images | grep bbsim
           '''
      }
    }

    stage('Install BBSIM w/SEBA') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           git clone https://gerrit.opencord.org/pod-configs
           pushd cord/helm-charts

           helm install -f examples/kafka-single.yaml --version 0.13.3 -n cord-kafka incubator/kafka

           git clone https://gerrit.opencord.org/helm-repo-tools
           helm-repo-tools/wait_for_pods.sh

           helm upgrade --install etcd-operator --version 0.8.3 stable/etcd-operator
           sleep 60
           JOBS_TIMEOUT=900 ./helm-repo-tools/wait_for_jobs.sh

           helm dep up voltha
           helm install -f $WORKSPACE/pod-configs/kubernetes-configs/${params.deploymentConfig} voltha -n voltha
           JOBS_TIMEOUT=900 ./helm-repo-tools/wait_for_pods.sh voltha

	       helm dep up onos
	       helm install onos -n onos -f $WORKSPACE/pod-configs/kubernetes-configs/${params.deploymentConfig}
           JOBS_TIMEOUT=900 ./helm-repo-tools/wait_for_pods.sh

           helm dep up xos-core
           helm install xos-core -n xos-core

           helm dep update xos-profiles/seba-services
           helm install xos-profiles/seba-services -n seba-services
           JOBS_TIMEOUT=900 ./helm-repo-tools/wait_for_pods.sh

           helm dep update xos-profiles/base-kubernetes
           helm install xos-profiles/base-kubernetes -n base-kubernetes

           helm dep update workflows/att-workflow
           helm install workflows/att-workflow -n att-workflow --set att-workflow-driver.kafkaService=cord-kafka

           # wait for services to load
           JOBS_TIMEOUT=900 ./helm-repo-tools/wait_for_jobs.sh

           echo "# Checking helm deployments"
           kubectl get pods
           helm list

           helm install --set images.bbsim.tag=latest --set images.bbsim.pullPolicy=IfNotPresent --set onus_per_pon_port=${params.OnuCount} ${params.EmulationMode} bbsim -n bbsim
           for hchart in \$(helm list -q);
           do
             echo "## 'helm status' for chart: \${hchart} ##"
             helm status "\${hchart}"
           done
           popd
           """
      }
    }
    stage('Load BBSIM-DHCP Tosca') {
      steps {
        sh '''
           #!/usr/bin/env bash
           set -eu -o pipefail
           pushd cord/helm-charts
           curl -H "xos-username: admin@opencord.org" -H "xos-password: letmein" -X POST --data-binary @examples/bbsim-dhcp.yaml http://127.0.0.1:30007/run
           popd
           '''
      }
    }
    stage('Test BBSIM') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail
           pushd cord/test/cord-tester/src/test/cord-api/Tests/BBSim
           robot -e serviceinstances -e onosdhcp -e notready -v number_of_onus:${params.OnuCount} BBSIMScale.robot || true
           """
      }
    }
  }

  post {
    always {
      sh '''
         kubectl get pods --all-namespaces
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
         for pod in \$(kubectl get pods -n voltha --no-headers | awk '{print \$1}');
         do
           if [[ \$pod == *"onos"* && \$pod != *"onos-service"* ]]; then
             kubectl logs -n voltha \$pod onos> $WORKSPACE/\$pod.log;
           else
             kubectl logs -n voltha \$pod> $WORKSPACE/\$pod.log;
           fi
         done

         # copy robot logs
         if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
         cp -r $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/BBSim/*ml ./RobotLogs

         echo "# removing helm deployments"
         kubectl get pods
         helm list
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
         step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "kailash@opennetworking.org", sendToIndividuals: false])

    }
  }
}
