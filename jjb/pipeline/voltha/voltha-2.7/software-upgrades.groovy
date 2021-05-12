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
      def extraHelmFlags = "${extraHelmFlags} --set global.log_level=DEBUG,onu=1,pon=1 --set onos-classic.replicas=3,onos-classic.atomix.replicas=3 "

      extraHelmFlags = extraHelmFlags + """ --set voltha.services.controller[0].service=voltha-infra-onos-classic-0.voltha-infra-onos-classic-hs.infra.svc \
      --set voltha.services.controller[0].port=6653 \
      --set voltha.services.controller[0].address=voltha-infra-onos-classic-0.voltha-infra-onos-classic-hs.infra.svc:6653 \
      --set voltha.services.controller[1].service=voltha-infra-onos-classic-1.voltha-infra-onos-classic-hs.infra.svc \
      --set voltha.services.controller[1].port=6653 \
      --set voltha.services.controller[1].address=voltha-infra-onos-classic-1.voltha-infra-onos-classic-hs.infra.svc:6653 \
      --set voltha.services.controller[2].service=voltha-infra-onos-classic-2.voltha-infra-onos-classic-hs.infra.svc \
      --set voltha.services.controller[2].port=6653 \
      --set voltha.services.controller[2].address=voltha-infra-onos-classic-2.voltha-infra-onos-classic-hs.infra.svc:6653 """
      //ONOS custom image handling
      if ( onosImg.trim() != '' ) {
         String[] split;
         onosImg = onosImg.trim()
         split = onosImg.split(':')
        extraHelmFlags = extraHelmFlags + "--set onos-classic.image.repository=" + split[0] +",onos-classic.image.tag=" + split[1] + " "
      }

      // Currently only testing with ATT workflow
      // TODO: Support for other workflows
      // NOTE localCharts is set to "true" so that we use the locally cloned version of the chart (set to voltha-2.7)
      volthaDeploy([workflow: "att", extraHelmFlags: extraHelmFlags, localCharts: true])
      // start logging
      sh """
      rm -rf $WORKSPACE/${name} || true
      mkdir -p $WORKSPACE/${name}
      _TAG=kail-${name} kail -n infra -n voltha > $WORKSPACE/${name}/onos-voltha-combined.log &
      """
      // forward ONOS and VOLTHA ports
      sh """
      _TAG=onos-port-forward bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n infra svc/voltha-infra-onos-classic-hs 8101:8101; done &"
      _TAG=onos-port-forward bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n infra svc/voltha-infra-onos-classic-hs 8181:8181; done &"
      _TAG=port-forward-voltha-api bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n voltha svc/voltha-voltha-api 55555:55555; done &"
      """
      sh """
      sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 log:set DEBUG org.opencord
      """
  }
  stage('Test - '+ name) {
      sh """
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/${name}"
        mkdir -p \$ROBOT_LOGS_DIR
        if [[ ${name} == 'onos-app-upgrade' ]]; then
          export ONOS_APPS_UNDER_TEST+=''
          if [ ${aaaVer.trim()} != '' ] && [ ${aaaOarUrl.trim()} != '' ]; then
            ONOS_APPS_UNDER_TEST+="org.opencord.aaa,${aaaVer.trim()},${aaaOarUrl.trim()}*"
          fi
          if [ ${oltVer.trim()} != '' ] && [ ${oltOarUrl.trim()} != '' ]; then
            ONOS_APPS_UNDER_TEST+="org.opencord.olt,${oltVer.trim()},${oltOarUrl.trim()}*"
          fi
          if [ ${dhcpl2relayVer.trim()} != '' ] && [ ${dhcpl2relayOarUrl.trim()} != '' ]; then
            ONOS_APPS_UNDER_TEST+="org.opencord.dhcpl2relay,${dhcpl2relayVer.trim()},${dhcpl2relayOarUrl.trim()}*"
          fi
          if [ ${igmpproxyVer.trim()} != '' ] && [ ${igmpproxyOarUrl.trim()} != '' ]; then
            ONOS_APPS_UNDER_TEST+="org.opencord.igmpproxy,${igmpproxyVer.trim()},${igmpproxyOarUrl.trim()}*"
          fi
          if [ ${sadisVer.trim()} != '' ] && [ ${sadisOarUrl.trim()} != '' ]; then
            ONOS_APPS_UNDER_TEST+="org.opencord.sadis,${sadisVer.trim()},${sadisOarUrl.trim()}*"
          fi
          if [ ${mcastVer.trim()} != '' ] && [ ${mcastOarUrl.trim()} != '' ]; then
            ONOS_APPS_UNDER_TEST+="org.opencord.mcast,${mcastVer.trim()},${mcastOarUrl.trim()}*"
          fi
          if [ ${kafkaVer.trim()} != '' ] && [ ${kafkaOarUrl.trim()} != '' ]; then
            ONOS_APPS_UNDER_TEST+="org.opencord.kafka,${kafkaVer.trim()},${kafkaOarUrl.trim()}*"
          fi
          export ROBOT_MISC_ARGS="-d \$ROBOT_LOGS_DIR -v onos_apps_under_test:\$ONOS_APPS_UNDER_TEST -e PowerSwitch"
          export TARGET=onos-app-upgrade-test
        fi
        if [[ ${name} == 'voltha-component-upgrade' ]]; then
          export VOLTHA_COMPS_UNDER_TEST+=''
          if [ ${adapterOpenOltImage.trim()} != '' ]; then
            VOLTHA_COMPS_UNDER_TEST+="adapter-open-olt,adapter-open-olt,${adapterOpenOltImage.trim()}*"
          fi
          if [ ${adapterOpenOnuImage.trim()} != '' ]; then
            VOLTHA_COMPS_UNDER_TEST+="adapter-open-onu,adapter-open-onu,${adapterOpenOnuImage.trim()}*"
          fi
          if [ ${rwCoreImage.trim()} != '' ]; then
            VOLTHA_COMPS_UNDER_TEST+="rw-core,voltha,${rwCoreImage.trim()}*"
          fi
          if [ ${ofAgentImage.trim()} != '' ]; then
            VOLTHA_COMPS_UNDER_TEST+="ofagent,ofagent,${ofAgentImage.trim()}*"
          fi
          export ROBOT_MISC_ARGS="-d \$ROBOT_LOGS_DIR -v voltha_comps_under_test:\$VOLTHA_COMPS_UNDER_TEST -e PowerSwitch"
          export TARGET=voltha-comp-upgrade-test
        fi
        if [[ ${name} == 'onu-software-upgrade' ]]; then
          export ROBOT_MISC_ARGS="-d \$ROBOT_LOGS_DIR -v onu_image_name:${onuImageName.trim()} -v onu_image_url:${onuImageUrl.trim()} -v onu_image_version:${onuImageVersion.trim()} -v onu_image_crc:${onuImageCrc.trim()} -v onu_image_local_dir:${onuImageLocalDir.trim()} -e PowerSwitch"
          export TARGET=onu-upgrade-test
        fi
        export VOLTCONFIG=$HOME/.volt/config-minimal
        export KUBECONFIG=$HOME/.kube/kind-config-voltha-minimal
        # Run the specified tests
        make -C $WORKSPACE/voltha-system-tests \$TARGET || true
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
        ps aux | grep port-forw | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9 || true
      """
      // collect pod details
      get_pods_info("$WORKSPACE/${name}")
      helmTeardown(['infra', 'voltha'])
  }
}
def get_pods_info(dest) {
  // collect pod details, this is here in case of failure
  sh """
  mkdir -p ${dest} || true
  kubectl get pods --all-namespaces -o wide > ${dest}/pods.txt || true
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq | tee ${dest}/pod-images.txt || true
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq | tee ${dest}/pod-imagesId.txt || true
  kubectl describe pods --all-namespaces -l app.kubernetes.io/part-of=voltha > ${dest}/voltha-pods-describe.txt
  kubectl describe pods -n infra -l app=onos-classic > ${dest}/onos-pods-describe.txt
  helm ls --all-namespaces > ${dest}/helm-charts.txt
  """
  sh '''
  # copy the ONOS logs directly from the container to avoid the color codes
  printf '%s\\n' $(kubectl get pods -n infra -l app=onos-classic -o=jsonpath="{.items[*]['metadata.name']}") | xargs --no-run-if-empty -I# bash -c 'kubectl -n infra cp #:apache-karaf-4.2.9/data/log/karaf.log ''' + dest + '''/#.log' || true
  '''
}
pipeline {
  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: 40, unit: 'MINUTES')
  }
  environment {
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin"
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    SSHPASS="karaf"
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
    stage('Cleanup') {
      steps {
        // remove port-forwarding
        sh """
          # remove orphaned port-forward from different namespaces
          ps aux | grep port-forw | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9 || true
        """
        helmTeardown(['infra', 'voltha'])
      }
    }
    stage('Install latest voltctl') {
      steps {
        sh """
          mkdir -p $WORKSPACE/bin || true
          # install voltctl
          HOSTOS="\$(uname -s | tr "[:upper:]" "[:lower:"])"
          HOSTARCH="\$(uname -m | tr "[:upper:]" "[:lower:"])"
          if [ "\$HOSTARCH" == "x86_64" ]; then
              HOSTARCH="amd64"
          fi
          VC_VERSION="\$(curl --fail -sSL https://api.github.com/repos/opencord/voltctl/releases/latest | jq -r .tag_name | sed -e 's/^v//g')"
          curl -Lo $WORKSPACE/bin/voltctl https://github.com/opencord/voltctl/releases/download/v\$VC_VERSION/voltctl-\$VC_VERSION-\$HOSTOS-\$HOSTARCH
          chmod +x $WORKSPACE/bin/voltctl
        """
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
        test_software_upgrade("onu-software-upgrade")
      }
    }
  }
  post {
    aborted {
      get_pods_info("$WORKSPACE/failed")
    }
    failure {
      get_pods_info("$WORKSPACE/failed")
    }
    always {
      sh '''
      gzip $WORKSPACE/onos-app-upgrade/onos-voltha-combined.log || true
      gzip $WORKSPACE/voltha-component-upgrade/onos-voltha-combined.log || true
      gzip $WORKSPACE/onu-software-upgrade/onos-voltha-combined.log || true
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
      archiveArtifacts artifacts: '*.log,**/*.log,**/*.gz,*.gz,*.txt,**/*.txt'
    }
  }
}
