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
    PATH="$PATH:$WORKSPACE/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    TYPE="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="no"
    WITH_RADIUS="yes"
    WITH_BBSIM="yes"
    LEGACY_BBSIM_INDEX="no"
    DEPLOY_K8S="no"
    CONFIG_SADIS="external"
    WITH_KAFKA="kafka.default.svc.cluster.local"
    WITH_ETCD="etcd-cluster-client.default.svc.cluster.local"

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
    WITH_PPROF="${withProfiling}"
    EXTRA_HELM_FLAGS="${extraHelmFlags} " // note that the trailing space is required to separate the parameters from appends done later

    VOLTHA_CHART="${volthaChart}"
    VOLTHA_BBSIM_CHART="${bbsimChart}"
    VOLTHA_ADAPTER_OPEN_OLT_CHART="${openoltAdapterChart}"
    VOLTHA_ADAPTER_OPEN_ONU_CHART="${openonuAdapterChart}"
  }

  stages {
    stage ('Cleanup') {
      steps {
        timeout(time: 11, unit: 'MINUTES') {
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
        script {
          sh(script:"""
            if [ ${volthaSystemTestsChange} != '' ] ; then
              cd voltha-system-tests;
              git fetch https://gerrit.opencord.org/voltha-system-tests ${volthaSystemTestsChange} && git checkout FETCH_HEAD
            fi
            """)
        }
      }
    }
    stage('Deploy common infrastructure') {
      // includes monitoring, kafka, etcd
      steps {
        sh '''
        helm install -n kafka incubator/kafka --version 0.13.3 --set replicas=3 --set persistence.enabled=false --set zookeeper.replicaCount=3 --set zookeeper.persistence.enabled=false --version=0.15.3

        helm install --set clusterName=etcd-cluster --set autoCompactionRetention=1 --set clusterSize=3 --set defaults.log_level=WARN --namespace default -n etcd-cluster onf/voltha-etcd-cluster ${extraHelmFlags}

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

          # Set Flows/Ports/Meters poll frequency
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.provider.of.flow.impl.OpenFlowRuleProvider flowPollFrequency ${onosStatInterval}
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.provider.of.device.impl.OpenFlowDeviceProvider portStatsPollFrequency ${onosStatInterval}

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

          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 log:set DEBUG org.onosproject.net.device.impl.DeviceManager
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 log:set DEBUG org.onosproject.openflow.controller.impl.OpenFlowControllerImpl
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 log:set DEBUG org.onosproject.openflow.controller.impl.OFChannelHandler
        '''
      }
    }
    stage('Run Test') {
      steps {
        sh '''
          mkdir -p $WORKSPACE/RobotLogs
          cd $WORKSPACE/voltha-system-tests
          make vst_venv
        '''
        sh '''
          if [ ${withProfiling} = true ] ; then
            mkdir -p $WORKSPACE/logs/pprof
            echo $PATH
            #Creating Python script for ONU Detection
            cat << EOF > $WORKSPACE/pprof.sh
timestamp() {
  date +"%T"
}

i=0
while [[ true ]]; do
  ((i++))
  ts=$(timestamp)
  go tool pprof -png http://127.0.0.1:6060/debug/pprof/heap > $WORKSPACE/logs/pprof/rw-core-heap-\\$i-\\$ts.png
  go tool pprof -png http://127.0.0.1:6060/debug/pprof/goroutine > $WORKSPACE/logs/pprof/rw-core-goroutine-\\$i-\\$ts.png
  curl -o $WORKSPACE/logs/pprof/rw-core-profile-\\$i-\\$ts.pprof http://127.0.0.1:6060/debug/pprof/profile?seconds=10
  go tool pprof -png $WORKSPACE/logs/pprof/rw-core-profile-\\$i-\\$ts.pprof > $WORKSPACE/logs/pprof/rw-core-profile-\\$i-\\$ts.png

  go tool pprof -png http://127.0.0.1:6061/debug/pprof/heap > $WORKSPACE/logs/pprof/openolt-heap-\\$i-\\$ts.png
  go tool pprof -png http://127.0.0.1:6061/debug/pprof/goroutine > $WORKSPACE/logs/pprof/openolt-goroutine-\\$i-\\$ts.png
  curl -o $WORKSPACE/logs/pprof/openolt-profile-\\$i-\\$ts.pprof http://127.0.0.1:6061/debug/pprof/profile?seconds=10
  go tool pprof -png $WORKSPACE/logs/pprof/openolt-profile-\\$i-\\$ts.pprof > $WORKSPACE/logs/pprof/openolt-profile-\\$i-\\$ts.png

  go tool pprof -png http://127.0.0.1:6062/debug/pprof/heap > $WORKSPACE/logs/pprof/ofagent-heap-\\$i-\\$ts.png
  go tool pprof -png http://127.0.0.1:6062/debug/pprof/goroutine > $WORKSPACE/logs/pprof/ofagent-goroutine-\\$i-\\$ts.png
  curl -o $WORKSPACE/logs/pprof/ofagent-profile-\\$i-\\$ts.pprof http://127.0.0.1:6062/debug/pprof/profile?seconds=10
  go tool pprof -png $WORKSPACE/logs/pprof/ofagent-profile-\\$i-\\$ts.pprof > $WORKSPACE/logs/pprof/ofagent-profile-\\$i-\\$ts.png

  sleep 10
done
EOF

            _TAG="pprof"
            _TAG=$_TAG bash $WORKSPACE/pprof.sh &
          fi
        '''
        timeout(time: 11, unit: 'MINUTES') {
          sh '''
            ROBOT_PARAMS="-v olt:${olts} \
              -v pon:${pons} \
              -v onu:${onus} \
              -v workflow:${workflow} \
              -v withEapol:${withEapol} \
              -v withDhcp:${withDhcp} \
              -v withIgmp:${withIgmp} \
              --noncritical non-critical \
              -e teardown "

            if [ ${withEapol} = false ] ; then
              ROBOT_PARAMS+="-e authentication "
            fi

            if [ ${withDhcp} = false ] ; then
              ROBOT_PARAMS+="-e dhcp "
            fi

            if [ ${provisionSubscribers} = false ] ; then
              # if we're not considering subscribers then we don't care about authentication and dhcp
              ROBOT_PARAMS+="-e authentication -e provision -e flow-after -e dhcp "
            fi

            if [ ${withFlows} = false ] ; then
              ROBOT_PARAMS+="-i setup -i activation "
            fi

            cd $WORKSPACE/voltha-system-tests
            source ./vst_venv/bin/activate
            robot -d $WORKSPACE/RobotLogs \
            $ROBOT_PARAMS tests/scale/Voltha_Scale_Tests.robot
          '''
        }
      }
    }
  }
  post {
    always {
      // collect result, done in the "post" step so it's executed even in the
      // event of a timeout in the tests
      sh '''
        cd voltha-system-tests
        source ./vst_venv/bin/activate
        python tests/scale/collect-result.py -r $WORKSPACE/RobotLogs/output.xml -p $WORKSPACE/plots > $WORKSPACE/execution-time.txt
        cat $WORKSPACE/execution-time.txt
      '''
      sh '''
        if [ ${withProfiling} = true ] ; then
          _TAG="pprof"
          P_IDS="$(ps e -ww -A | grep "_TAG=$_TAG" | grep -v grep | awk '{print $1}')"
          if [ -n "$P_IDS" ]; then
            echo $P_IDS
            for P_ID in $P_IDS; do
              kill -9 $P_ID
            done
          fi
        fi
      '''
      plot([
        csvFileName: 'scale-test.csv',
        csvSeries: [
          [file: 'plots/plot-voltha-onus.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-onos-ports.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-voltha-flows-before.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-voltha-openolt-flows-before.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-onos-flows-before.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-onos-auth.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-voltha-flows-after.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-voltha-openolt-flows-after.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-onos-flows-after.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-onos-dhcp.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
        ],
        group: 'Voltha-Scale-Numbers', numBuilds: '20', style: 'line', title: "Scale Test (OLTs: ${olts}, PONs: ${pons}, ONUs: ${onus})", yaxis: 'Time (s)', useDescr: true
      ])
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: 'RobotLogs/log.html',
        otherFiles: '',
        outputFileName: 'RobotLogs/output.xml',
        outputPath: '.',
        passThreshold: 100,
        reportFileName: 'RobotLogs/report.html',
        unstableThreshold: 0]);
      // get all the logs from kubernetes PODs
      sh '''
        mkdir -p $WORKSPACE/logs
        kubectl get pods -o wide > $WORKSPACE/logs/pods.txt
        kubectl logs -l app=adapter-open-olt > $WORKSPACE/logs/open-olt-logs.logs
        kubectl logs -l app=adapter-open-onu > $WORKSPACE/logs/open-onu-logs.logs
        kubectl logs -l app=rw-core > $WORKSPACE/logs/voltha-rw-core-logs.logs
        kubectl logs -l app=ofagent > $WORKSPACE/logs/voltha-ofagent-logs.logs
        kubectl logs -l app=bbsim > $WORKSPACE/logs/bbsim-logs.logs
        kubectl logs -l app=onos > $WORKSPACE/logs/onos-logs.logs
        kubectl logs -l app=onos-onos-classic > $WORKSPACE/logs/onos-onos-classic-logs.logs
        kubectl logs -l app=etcd > $WORKSPACE/logs/etcd-logs.logs
        kubectl logs -l app=kafka > $WORKSPACE/logs/kafka-logs.logs
      '''
      // count how many ONUs have been activated
      sh '''
        echo "#-of-ONUs" > $WORKSPACE/logs/voltha-devices-count.txt
        echo $(voltctl device list | grep -v OLT | grep ACTIVE | wc -l) >> $WORKSPACE/logs/voltha-devices-count.txt
      '''
      // count how many ports have been discovered
      sh '''
        echo "#-of-ports" > $WORKSPACE/logs/onos-ports-count.txt
        echo $(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 ports -e | grep BBSM | wc -l) >> $WORKSPACE/logs/onos-ports-count.txt
      '''
      // count how many flows have been provisioned
      sh '''
        echo "#-of-flows" > $WORKSPACE/logs/onos-flows-count.txt
        echo $(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 flows -s | grep ADDED | wc -l) >> $WORKSPACE/logs/onos-flows-count.txt
      '''
      // dump and count the authenticated users
      sh '''
        if [ ${withOnosApps} = true ] && [ ${bbsimAuth} ] ; then
          echo $(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 aaa-users) >> $WORKSPACE/logs/onos-aaa-users.txt

          echo "#-of-authenticated-users" > $WORKSPACE/logs/onos-aaa-count.txt
          echo $(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 aaa-users | grep AUTHORIZED_STATE | wc -l) >> $WORKSPACE/logs/onos-aaa-count.txt
        fi
      '''
      // dump and count the dhcp users
      sh '''
        if [ ${withOnosApps} = true ] && [ ${bbsimDhcp} ] ; then
          echo $(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 dhcpl2relay-allocations) >> $WORKSPACE/logs/onos-dhcp-allocations.txt

          echo "#-of-dhcp-allocations" > $WORKSPACE/logs/onos-dhcp-count.txt
          echo $(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 dhcpl2relay-allocations | grep DHCPACK | wc -l) >> $WORKSPACE/logs/onos-dhcp-count.txt
        fi
      '''
      // check which containers were used in this build
      sh '''
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq
      '''
      // dump all the BBSim(s) ONU informations
      sh '''
      BBSIM_IDS=$(kubectl get pods | grep bbsim | grep -v server | awk '{print $1}')
      IDS=($BBSIM_IDS)

      for bbsim in "${IDS[@]}"
      do
        kubectl exec -t $bbsim bbsimctl onu list > $WORKSPACE/logs/$bbsim-device-list.txt
      done
      '''
      // get ports and flows from ONOS
      sh '''
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 ports > $WORKSPACE/logs/onos-ports-list.txt
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 flows -s > $WORKSPACE/logs/onos-flows-list.txt
      '''
      // get cpu usage by container
      sh '''
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=avg(rate(container_cpu_usage_seconds_total[10m])*100) by (pod_name)' | jq . > $WORKSPACE/cpu-usage.json
      '''
      // collect etcd metrics
      sh '''
        mkdir $WORKSPACE/etcd-metrics
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_debugging_mvcc_keys_total' | jq . > $WORKSPACE/etcd-metrics/etcd-key-count.json
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=grpc_server_handled_total{grpc_code="OK",grpc_service="etcdserverpb.KV"}' | jq . > $WORKSPACE/etcd-metrics/etcd-rpc-count.json
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_debugging_mvcc_db_total_size_in_bytes' | jq '.data.result[0].value[1]' > $WORKSPACE/etcd-metrics/etcd-db-size.json
      '''
      // dump all the VOLTHA devices informations
      script {
        try {
          sh '''
          voltctl device list -o json > $WORKSPACE/logs/device-list.json
          python -m json.tool $WORKSPACE/logs/device-list.json > $WORKSPACE/logs/voltha-devices-list.json
          '''
        } catch(e) {
          sh '''
          echo "Can't get device list from voltclt"
          '''
        }
      }
      archiveArtifacts artifacts: 'kind-voltha/install-minimal.log,execution-time.txt,logs/*,logs/pprof/*,RobotLogs/*,plots/*.txt'
    }
  }
}
