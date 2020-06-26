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
    JENKINS_NODE_COOKIE="dontKillMe" // do not kill processes after the build is done
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
    WITH_ETCD="etcd.default.svc.cluster.local"
    VOLTHA_ETCD_PORT=9999

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
            helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com
            helm repo add stable https://kubernetes-charts.storage.googleapis.com
            helm repo add onf https://charts.opencord.org
            helm repo add cord https://charts.opencord.org
            helm repo add onos https://charts.onosproject.org
            helm repo add atomix https://charts.atomix.io
            helm repo add bbsim-sadis https://ciena.github.io/bbsim-sadis-server/charts
            helm repo update

            # removing ETCD port forward
            P_ID="\$(ps e -ww -A | grep "_TAG=etcd-port-forward" | grep -v grep | awk '{print \$1}')"
            if [ -n "\$P_ID" ]; then
              kill -9 \$P_ID
            fi

            for hchart in \$(helm list -q | grep -E -v 'docker-registry|kafkacat');
            do
                echo "Purging chart: \${hchart}"
                helm delete --purge "\${hchart}"
            done
            bash /home/cord/voltha-scale/wait_for_pods.sh

            test -e $WORKSPACE/kind-voltha/voltha && cd $WORKSPACE/kind-voltha && ./voltha down

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
        script {
          sh(script:"""
          if [ '${kindVolthaChange}' != '' ] ; then
          cd $WORKSPACE/kind-voltha;
          git fetch https://gerrit.opencord.org/kind-voltha ${volthaSystemTestsChange} && git checkout FETCH_HEAD
          fi
          """)
        }
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
            if [ '${volthaSystemTestsChange}' != '' ] ; then
              cd $WORKSPACE/voltha-system-tests;
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

        # the ETCD chart use "auth" for resons different than BBsim, so strip that away
        ETCD_FLAGS=$(echo ${extraHelmFlags} | sed -e 's/--set auth=false / /g') | sed -e 's/--set auth=true / /g'
        ETCD_FLAGS+=" --set memoryMode=${inMemoryEtcdStorage} "
        helm install -f $WORKSPACE/kind-voltha/minimal-values.yaml --set etcd.replicas=3 -n etcd incubator/etcd $ETCD_FLAGS

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
          sh returnStdout: false, script: """
            export EXTRA_HELM_FLAGS+='--set enablePerf=true,pon=${pons},onu=${onus} '

            # disable the securityContext, this is a development cluster
            EXTRA_HELM_FLAGS+='--set securityContext.enabled=false '

            # BBSim custom image handling
            IFS=: read -r bbsimRepo bbsimTag <<< ${bbsimImg}
            EXTRA_HELM_FLAGS+="--set images.bbsim.repository=\$bbsimRepo,images.bbsim.tag=\$bbsimTag "

            # VOLTHA and ofAgent custom image handling
            IFS=: read -r rwCoreRepo rwCoreTag <<< ${rwCoreImg}
            IFS=: read -r ofAgentRepo ofAgentTag <<< ${ofAgentImg}
            EXTRA_HELM_FLAGS+="--set images.rw_core.repository=\$rwCoreRepo,images.rw_core.tag=\$rwCoreTag,images.ofagent.repository=\$ofAgentRepo,images.ofagent.tag=\$ofAgentTag "

            # OpenOLT custom image handling
            IFS=: read -r openoltAdapterRepo openoltAdapterTag <<< ${openoltAdapterImg}
            EXTRA_HELM_FLAGS+="--set images.adapter_open_olt.repository=\$openoltAdapterRepo,images.adapter_open_olt.tag=\$openoltAdapterTag "

            # OpenONU custom image handling
            IFS=: read -r openonuAdapterRepo openonuAdapterTag <<< ${openonuAdapterImg}
            EXTRA_HELM_FLAGS+="--set images.adapter_open_onu.repository=\$openonuAdapterRepo,images.adapter_open_onu.tag=\$openonuAdapterTag "

            # ONOS custom image handling
            IFS=: read -r onosRepo onosTag <<< ${onosImg}
            EXTRA_HELM_FLAGS+="--set images.onos.repository=\$onosRepo,images.onos.tag=\$onosTag "

            # No persistent-volume-claims in Atomix
            EXTRA_HELM_FLAGS+="--set atomix.persistence.enabled=false "

            cd $WORKSPACE/kind-voltha/

            ./voltha up

            # Forward the ETCD port onto $VOLTHA_ETCD_PORT
            _TAG=etcd-port-forward kubectl port-forward --address 0.0.0.0 -n default service/etcd $VOLTHA_ETCD_PORT:2379&
          """
        }
        // bbsim-sadis server takes a while to cache the subscriber entries
        // wait for that before starting the tests
        sleep(120)
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
            cat BBSM-12345123451234512345-00000000000001-v1.json | kubectl exec -it $(kubectl get pods -l app=etcd | awk 'NR==2{print $1}') etcdctl put service/voltha/omci_mibs/templates/BBSM/12345123451234512345/00000000000001
          fi

          # Start the tcp-dump in ofagent
          if [ ${withPcap} = true ] ; then
            export OF_AGENT=$(kubectl get pods -l app=ofagent | awk 'NR==2{print $1}')
            kubectl exec $OF_AGENT -- apk update
            kubectl exec $OF_AGENT -- apk add tcpdump
            kubectl exec $OF_AGENT -- mv /usr/sbin/tcpdump /usr/bin/tcpdump
            _TAG=ofagent-tcpdump kubectl exec $OF_AGENT -- tcpdump -nei eth0 -w out.pcap&
          fi
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

        if [ ${withPcap} = true ] ; then
          # stop ofAgent tcpdump
          P_ID="\$(ps e -ww -A | grep "_TAG=ofagent-tcpdump" | grep -v grep | awk '{print \$1}')"
          if [ -n "\$P_ID" ]; then
            kill -9 \$P_ID
          fi

          # copy the file
          export OF_AGENT=$(kubectl get pods -l app=ofagent | awk 'NR==2{print $1}')
          kubectl cp $OF_AGENT:out.pcap $WORKSPACE/logs/ofagent.pcap
        fi

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
      sh returnStdout: false, script: '''
        LOG_FOLDER=$WORKSPACE/logs
        mkdir -p $LOG_FOLDER

        # store information on the running pods
        kubectl get pods -o wide > $LOG_FOLDER/pods.txt
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq | tee $LOG_FOLDER/pod-images.txt
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq | tee $LOG_FOLDER/pod-imagesId.txt

        # log in individual files for all the container that match the selector app=$APP_TO_LOG
        APPS_TO_LOG=(etcd kafka onos adapter-open-onu adapter-open-olt rw-core ofagent bbsim)
        for app in "${APPS_TO_LOG[@]}"
        do
          echo "Getting logs for: ${app}"
          kubectl get pods -l app=${app} -o=jsonpath=\"{.items[*]['metadata.name']}\"
          printf '%s\n' $(kubectl get pods -l app=$app -o=jsonpath="{.items[*]['metadata.name']}") | xargs -I@ bash -c "kubectl logs @ > $LOG_FOLDER/@.log"

          # Get the logs from the previous POD if any (useful in case of restarts)
          printf '%s\n' $(kubectl get pods -l app=$app -o=jsonpath="{.items[*]['metadata.name']}") | xargs -I@ bash -c "kubectl logs -p @ > $LOG_FOLDER/@-previous.log" || true
        done

        # copy the ONOS logs directly from the container to avoid the color codes
        printf '%s\n' $(kubectl get pods -l app=onos-onos-classic -o=jsonpath="{.items[*]['metadata.name']}") | xargs -I@ bash -c "kubectl cp @:apache-karaf-4.2.8/data/log/karaf.log $LOG_FOLDER/@.log"
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
      // get ONOS debug infos
      sh '''
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 ports > $WORKSPACE/logs/onos-ports-list.txt

        if [ ${withFlows} = true ] ; then
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 flows -s > $WORKSPACE/logs/onos-flows-list.txt
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 meters > $WORKSPACE/logs/onos-meters-list.txt
        fi

        if [ ${provisionSubscribers} = true ]; then
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 volt-programmed-subscribers > $WORKSPACE/logs/onos-programmed-subscribers.txt
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 volt-programmed-meters > $WORKSPACE/logs/onos-programmed-meters.txt
        fi

        if [ ${withEapol} = true ] ; then
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 aaa-users > $WORKSPACE/logs/onos-aaa-users.txt
        fi

        if [ ${withDhcp} = true ] ; then
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 dhcpl2relay-allocations > $WORKSPACE/logs/onos-dhcp-allocations.txt
        fi
      '''
      // get cpu usage by container
      sh '''
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=avg(rate(container_cpu_usage_seconds_total[10m])*100) by (pod_name)' | jq . > $WORKSPACE/cpu-usage.json
      '''
      // collect etcd metrics
      sh '''
        mkdir $WORKSPACE/etcd-metrics
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_debugging_mvcc_keys_total' | jq '.data' > $WORKSPACE/etcd-metrics/etcd-key-count.json
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=grpc_server_handled_total{grpc_service="etcdserverpb.KV"}' | jq '.data' > $WORKSPACE/etcd-metrics/etcd-rpc-count.json
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_debugging_mvcc_db_total_size_in_bytes' | jq '.data' > $WORKSPACE/etcd-metrics/etcd-db-size.json
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_disk_backend_commit_duration_seconds_sum' | jq '.data'  > $WORKSPACE/etcd-metrics/etcd-backend-write-time.json
      '''
      // get VOLTHA debug infos
      script {
        try {
          sh '''
          voltctl device list -o json > $WORKSPACE/logs/device-list.json
          python -m json.tool $WORKSPACE/logs/device-list.json > $WORKSPACE/logs/voltha-devices-list.json
          rm $WORKSPACE/logs/device-list.json
          voltctl device list > $WORKSPACE/logs/voltha-devices-list.txt

          printf '%s\n' $(voltctl device list | grep olt | awk '{print $1}') | xargs -I@ bash -c "voltctl device flows @ > $WORKSPACE/logs/voltha-device-flows-@.txt"
          printf '%s\n' $(voltctl device list | grep olt | awk '{print $1}') | xargs -I@ bash -c "voltctl device port list --format 'table{{.PortNo}}\t{{.Label}}\t{{.Type}}\t{{.AdminState}}\t{{.OperStatus}}' @ > $WORKSPACE/logs/voltha-device-ports-@.txt"

          printf '%s\n' $(voltctl logicaldevice list -q) | xargs -I@ bash -c "voltctl logicaldevice flows @ > $WORKSPACE/logs/voltha-logicaldevice-flows-@.txt"
          printf '%s\n' $(voltctl logicaldevice list -q) | xargs -I@ bash -c "voltctl logicaldevice port list @ > $WORKSPACE/logs/voltha-logicaldevice-ports-@.txt"
          '''
        } catch(e) {
          sh '''
          echo "Can't get device list from voltclt"
          '''
        }
      }
      archiveArtifacts artifacts: 'kind-voltha/install-minimal.log,execution-time.txt,logs/*,logs/pprof/*,RobotLogs/*,plots/*.txt,etcd-metrics/*'
    }
  }
}
