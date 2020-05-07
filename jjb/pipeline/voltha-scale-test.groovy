// Copyright 2019-present Open Networking Foundation
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

// deploy VOLTHA using kind-voltha and performs a scale test

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 30, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/config"
    VOLTCONFIG="$HOME/.volt/config"
    SSHPASS="karaf"
    PATH="$WORKSPACE/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    TYPE="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="no"
    WITH_RADIUS="yes"
    WITH_BBSIM="yes"
    LEGACY_BBSIM_INDEX="no"
    DEPLOY_K8S="no"
    CONFIG_SADIS="external"
    WITH_KAFKA="kafka.default.svc.cluster.local"
    WITH_ETCD="external"

    // install everything in the default namespace
    VOLTHA_NS="default"
    ADAPTER_NS="default"
    INFRA_NS="default"
    BBSIM_NS="default"

    // configurable options
    WITH_EAPOL="${withEapol}"
    WITH_DHCP="${withDhcp}"
    WITH_IGMP="${withIgmp}"
    VOLTHA_LOG_LEVEL="${logLevel}"
    NUM_OF_BBSIM="${olts}"
    NUM_OF_OPENONU="${openonuAdapterReplicas}"
    NUM_OF_ONOS="${onosReplicas}"
    NUM_OF_ATOMIX="${atomixReplicas}"

    VOLTHA_CHART="${volthaChart}"
    VOLTHA_BBSIM_CHART="${bbsimChart}"
    VOLTHA_ADAPTER_OPEN_OLT_CHART="${openoltAdapterChart}"
    VOLTHA_ADAPTER_OPEN_ONU_CHART="${openonuAdapterChart}"
  }

  stages {
    stage ('Cleanup') {
      steps {
        sh returnStdout: false, script: """
        test -e $WORKSPACE/kind-voltha/voltha && cd $WORKSPACE/kind-voltha && ./voltha down

        for hchart in \$(helm list -q | grep -E -v 'docker-registry|kafkacat|etcd-operator');
        do
            echo "Purging chart: \${hchart}"
            helm delete --purge "\${hchart}"
        done
        bash /home/cord/voltha-scale/wait_for_pods.sh

        cd $WORKSPACE
        rm -rf $WORKSPACE/*
        """
      }
    }
    stage('Clone kind-voltha') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "https://gerrit.opencord.org/kind-voltha", ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "kind-voltha"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
      }
    }
    stage('Clone voltha-system-tests') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "https://gerrit.opencord.org/voltha-system-tests", ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-system-tests"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
      }
    }
    stage('Deploy common infrastructure') {
      // includes monitoring, kafka
      steps {
        sh '''
        helm install -n kafka incubator/kafka --version 0.13.3 --set replicas=3 --set persistence.enabled=false --set zookeeper.replicaCount=3 --set zookeeper.persistence.enabled=false --version=0.15.3

        if [ ${withMonitoring} = true ] ; then
          helm install -n nem-monitoring cord/nem-monitoring \
          --set prometheus.alertmanager.enabled=false,prometheus.pushgateway.enabled=false \
          --set kpi_exporter.enabled=false,dashboards.xos=false,dashboards.onos=false,dashboards.aaa=false,dashboards.voltha=false
        fi

        # TODO download this file from https://github.com/opencord/helm-charts/blob/master/scripts/wait_for_pods.sh
        bash /home/cord/voltha-scale/wait_for_pods.sh
        '''
      }
    }
    stage('Deploy Voltha') {
      steps {
        script {
          // TODO install etcd outside kind-voltha (no need to redeploy the operator everytime)
          sh returnStdout: false, script: """
            export EXTRA_HELM_FLAGS+='--set enablePerf=true,pon=${pons},onu=${onus} '

            # BBSim custom image handling
            IFS=: read -r bbsimRepo bbsimTag <<< ${bbsimImg}
            EXTRA_HELM_FLAGS+="--set images.bbsim.repository=\$bbsimRepo,images.bbsim.tag=\$bbsimTag "

            # VOLTHA and ofAgent custom image handling
            IFS=: read -r rwCoreRepo rwCoreTag <<< ${rwCoreImg}
            IFS=: read -r ofAgentRepo ofAgentTag <<< ${ofAgentImg}
            EXTRA_HELM_FLAGS+="--set images.rw_core.repository=\$rwCoreRepo,images.rw_core.tag=\$rwCoreTag,images.ofagent_go.repository=\$ofAgentRepo,images.ofagent_go.tag=\$ofAgentTag "

            # OpenOLT custom image handling
            IFS=: read -r openoltAdapterRepo openoltAdapterTag <<< ${openoltAdapterImg}
            EXTRA_HELM_FLAGS+="--set images.adapter_open_olt.repository=\$openoltAdapterRepo,images.adapter_open_olt.tag=\$openoltAdapterTag "

            # OpenONU custom image handling
            IFS=: read -r openonuAdapterRepo openonuAdapterTag <<< ${openonuAdapterImg}
            EXTRA_HELM_FLAGS+="--set images.adapter_open_onu.repository=\$openonuAdapterRepo,images.adapter_open_onu.tag=\$openonuAdapterTag "

            # ONOS custom image handling
            IFS=: read -r onosRepo onosTag <<< ${onosImg}
            EXTRA_HELM_FLAGS+="--set images.onos.repository=\$onosRepo,images.onos.tag=\$onosTag "


            cd $WORKSPACE/kind-voltha/

            ./voltha up
          """
        }
      }
    }
    stage('Configuration') {
      steps {
        sh '''
          # Always deactivate org.opencord.kafka
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 app deactivate org.opencord.kafka

          #Setting link discovery
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.provider.lldp.impl.LldpLinkProvider enabled ${withLLDP}

          #Setting LOG level to ${logLevel}
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 log:set ${logLevel}
          kubectl exec $(kubectl get pods | grep -E "bbsim[0-9]" | awk 'NR==1{print $1}') -- bbsimctl log ${logLevel} false

          if [ ${withEapol} = false ] || [ ${withFlows} = false ]; then
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 app deactivate org.opencord.aaa
          fi

          if [ ${withDhcp} = false ] || [ ${withFlows} = false ]; then
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 app deactivate org.opencord.dhcpl2relay
          fi

          if [ ${withIgmp} = false ] || [ ${withFlows} = false ]; then
            # FIXME will actually affected the tests only after VOL-3054 is addressed
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 app deactivate org.opencord.igmpproxy
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 app deactivate org.opencord.mcast
          fi

          if [ ${withFlows} = false ]; then
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 app deactivate org.opencord.olt
          fi

          if [ ${withMibTemplate} = true ] ; then
            rm -f BBSM-12345123451234512345-00000000000001-v1.json
            wget https://raw.githubusercontent.com/opencord/voltha-openonu-adapter/master/templates/BBSM-12345123451234512345-00000000000001-v1.json
            cat BBSM-12345123451234512345-00000000000001-v1.json | kubectl exec -it $(kubectl get pods | grep etcd-cluster | awk 'NR==1{print $1}') etcdctl put service/voltha/omci_mibs/templates/BBSM/12345123451234512345/00000000000001
          fi
        '''
      }
    }
    stage('Run Test') {
      steps {
        sh '''
          ROBOT_PARAMS="-v olt:${olts} \
            -v pon:${pons} \
            -v onu:${onus} \
            -v workflow:${workflow} \
            --noncritical non-critical \
            -e teardown "

          if [ ${withEapol} = false ] ; then
            ROBOT_PARAMS+="-e authentication "
          fi

          if [ ${withDhcp} = false ] ; then
            ROBOT_PARAMS+="-e dhcp "
          fi

          if [ ${provisionSubscribers} = false ] ; then
            ROBOT_PARAMS+="-e provision -e flow-after "
          fi

          if [ ${withFlows} = false ] ; then
            ROBOT_PARAMS+="-i setup -i activation "
          fi

          mkdir -p $WORKSPACE/RobotLogs
          cd voltha-system-tests
          make vst_venv
          source ./vst_venv/bin/activate
          robot -d $WORKSPACE/RobotLogs \
          $ROBOT_PARAMS tests/scale/Voltha_Scale_Tests.robot
        '''
      }
    }
    stage('Collect results') {
      steps {
        sh '''
          cd voltha-system-tests
          source ./vst_venv/bin/activate
          python tests/scale/collect-result.py -r $WORKSPACE/RobotLogs/output.xml -p $WORKSPACE/plots > $WORKSPACE/execution-time.txt
        '''
      }
    }
  }
  post {
    always {
      plot([
        csvFileName: 'scale-test.csv',
        csvSeries: [
          [file: '$WORKSPACE/plots/plot-voltha-onus.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: '$WORKSPACE/plots/plot-onos-ports.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: '$WORKSPACE/plots/plot-voltha-flows-before.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: '$WORKSPACE/plots/plot-onos-flows-before.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: '$WORKSPACE/plots/plot-onos-auth.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: '$WORKSPACE/plots/plot-voltha-flows-after.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: '$WORKSPACE/plots/plot-onos-flows-after.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: '$WORKSPACE/plots/plot-onos-dhcp.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
        ],
        group: 'Voltha-Scale-Numbers', numBuilds: '20', style: 'line', title: "Scale Test (OLTs: ${olts}, PONs: ${pons}, ONUs: ${onus})", yaxis: 'Time (s)', useDescr: true
      ])
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: '$WORKSPACE/RobotLogs/log*.html',
        otherFiles: '',
        outputFileName: '$WORKSPACE/RobotLogs/output*.xml',
        outputPath: '.',
        passThreshold: 100,
        reportFileName: '$WORKSPACE/RobotLogs/report*.html',
        unstableThreshold: 0]);
        // get all the logs from kubernetes PODs
      sh '''
        mkdir $WORKSPACE/logs
        kubectl get pods -o wide > $WORKSPACE/logs/pods.txt
        kubectl logs -l app=adapter-open-olt > $WORKSPACE/logs/open-olt-logs.logs
        kubectl logs -l app=adapter-open-onu > $WORKSPACE/logs/open-onu-logs.logs
        kubectl logs -l app=rw-core > $WORKSPACE/logs/voltha-rw-core-logs.logs
        kubectl logs -l app=ofagent > $WORKSPACE/logs/voltha-ofagent-logs.logs
        kubectl logs -l app=bbsim > $WORKSPACE/logs/bbsim-logs.logs
        kubectl logs -l app=onos > $WORKSPACE/logs/onos-logs.logs
      '''
      archiveArtifacts artifacts: '$WORKSPACE/kind-voltha/install-minimal.log,$WORKSPACE/execution-time.txt,$WORKSPACE/logs/*'
    }
  }
}
