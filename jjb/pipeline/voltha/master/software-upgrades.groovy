// Copyright 2021-present Open Networking Foundation
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
// uses bbsim to simulate OLT/ONUs

// NOTE we are importing the library even if it's global so that it's
// easier to change the keywords during a replay
library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://gerrit.opencord.org/ci-management.git'
])

def test_software_upgrade(name) {
  stage('Deploy Voltha - '+ name) {
      def extraHelmFlags = "${extraHelmFlags} --set global.log_level=DEBUG,onu=1,pon=1 "

      // TODO: ONOS custom image handling
      // if [ '${onosImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-onos' ]; then
      //   IFS=: read -r onosRepo onosTag <<< '${onosImg.trim()}'
      //   extraHelmFlags = extraHelmFlags + "--set onos-classic.images.onos.repository=\$onosRepo,onos-classic.images.onos.tag=\$onosTag "
      // fi

      def localCharts = false
      if (gerritProject == "voltha-helm-charts") {
        localCharts = true
      }

      // Currently only testing with ATT workflow
      // TODO: Support for other workflows
      volthaDeploy([workflow: "att", extraHelmFlags: extraHelmFlags, localCharts: localCharts])
      // start logging
      sh """
      mkdir -p $WORKSPACE/${name}
      _TAG=kail-${name} kail -n infra -n voltha > $WORKSPACE/${name}/onos-voltha-combined.log &
      """
      // forward ONOS and VOLTHA ports
      sh """
      _TAG=onos-port-forward kubectl port-forward --address 0.0.0.0 -n infra svc/voltha-infra-onos-classic-hs 8101:8101&
      _TAG=onos-port-forward kubectl port-forward --address 0.0.0.0 -n infra svc/voltha-infra-onos-classic-hs 8181:8181&
      _TAG=voltha-port-forward kubectl port-forward --address 0.0.0.0 -n voltha svc/voltha-voltha-api 55555:55555&
      """
  }
  stage('Test - '+ name) {
      sh """
      ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/${name}"
      mkdir -p \$ROBOT_LOGS_DIR

      if ('${name}' == 'onos-app-upgrade') {
        def onosAppsUnderTest = ""

        def testAaa = false
        if ('${aaaVer.trim()}' != '' && '${aaaOarUrl.trim()}' != '') {
          testAaa = true
          onosAppsUnderTest = onosAppsUnderTest + "org.opencord.aaa," + ${aaaVer.trim()} + "," + ${aaaOarUrl.trim()}
        }

        // TODO: Add other ONOS Apps

        export ROBOT_MISC_ARGS="-d \$ROBOT_LOGS_DIR -v onos_apps_under_test:${onosAppsUnderTest} -e PowerSwitch"

        export TARGET=onos-app-upgrade-test
      } else {
        def volthaCompsUnderTest = ""

        def testAdapterOpenOlt = false
        if ('${adapterOpenOltImage.trim()}' != '') {
          testAdapterOpenOlt = true
          volthaCompsUnderTest = volthaCompsUnderTest + "adapter-open-olt,adapter-open-olt," + ${adapterOpenOltImage.trim()}
        }

        // TODO: Add other Voltha Components

        export ROBOT_MISC_ARGS="-d \$ROBOT_LOGS_DIR -v voltha_comps_under_test:${volthaCompsUnderTest} -e PowerSwitch"

        export TARGET=voltha-comp-upgrade-test
      }

      export VOLTCONFIG=$HOME/.volt/config
      export KUBECONFIG=$HOME/.kube/config

      # Run the specified tests
      make -C $WORKSPACE/voltha-system-tests \$TARGET

      """
      // stop logging
      sh """
        P_IDS="\$(ps e -ww -A | grep "_TAG=kail-${name}" | grep -v grep | awk '{print \$1}')"
        if [ -n "\$P_IDS" ]; then
          echo \$P_IDS
          for P_ID in \$P_IDS; do
            kill -9 \$P_ID
          done
        fi
      """
      // remove port-forwarding
      sh """
        # remove orphaned port-forward from different namespaces
        ps aux | grep port-forw | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9
      """
      // collect pod details
      sh """
      kubectl get pods --all-namespaces -o wide > \$WORKSPACE/${name}/pods.txt || true
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq | tee \$WORKSPACE/${name}/pod-images.txt || true
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq | tee \$WORKSPACE/${name}/pod-imagesId.txt || true
      """
      helmTeardown(['infra', 'voltha'])
  }
}

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: 30, unit: 'MINUTES')
  }
  environment {
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin"
    KUBECONFIG="$HOME/.kube/kind-config-${clusterName}"
  }

  stages{
    stage('Download Code') {
      steps {
        getVolthaCode([
          branch: "${branch}",
          volthaSystemTestsChange: "${volthaSystemTestsChange}",
          volthaHelmChartsChange: "${volthaHelmChartsChange}",
        ])
      }
    }
    stage('Create K8s Cluster') {
      steps {
        createKubernetesCluster([nodes: 3])
      }
    }
    stage('Run Test') {
      steps {
        test_software_upgrade("onos-app-upgrade")
        test_software_upgrade("voltha-component-upgrade")
      }
    }
  }

  post {
    always {
      sh '''
      gzip $WORKSPACE/onos-app-upgrade/onos-voltha-combined.log || true
      gzip $WORKSPACE/voltha-component-upgrade/onos-voltha-combined.log || true
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
      archiveArtifacts artifacts: '*.log,**/*.log,**/*.gz,*.gz'
    }
  }
}
