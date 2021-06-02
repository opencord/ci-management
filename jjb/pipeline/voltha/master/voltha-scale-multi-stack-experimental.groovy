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

// NOTE we are importing the library even if it's global so that it's
// easier to change the keywords during a replay
library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://gerrit.opencord.org/ci-management.git'
])

def ofAgentConnections(numOfOnos, releaseName, namespace) {
    def params = " "
    numOfOnos.times {
        params += "--set voltha.services.controller[${it}].address=${releaseName}-onos-classic-${it}.${releaseName}-onos-classic-hs.${namespace}.svc:6653 "
    }
    return params
}

def maxStacks = 20

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 120, unit: 'MINUTES')
  }
  environment {
    JENKINS_NODE_COOKIE="dontKillMe" // do not kill processes after the build is done
    KUBECONFIG="$HOME/.kube/config"
    SSHPASS="karaf"
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin"

    APPS_TO_LOG="etcd kafka onos-onos-classic adapter-open-onu adapter-open-olt rw-core ofagent bbsim radius bbsim-sadis-server"
    LOG_FOLDER="$WORKSPACE/logs"
  }

  stages {
    stage ('Cleanup') {
      steps {
        timeout(time: 11, unit: 'MINUTES') {
          sh """
          # remove orphaned port-forward from different namespaces
            ps aux | grep port-forw | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9 || true
          """
          script {

            if (volthaStacks.toInteger() > 10) {
              error("The maximum allowed number of VOLTHA stacks is ${maxStacks}, you selected: ${volthaStacks}")
            }

            def namespaces = ["infra"]
            maxStacks.toInteger().times {
              namespaces += "voltha${it + 1}"
            }
            helmTeardown(namespaces)
          }
          sh returnStdout: false, script: """
            helm repo add onf https://charts.opencord.org
            helm repo add cord https://charts.opencord.org
            helm repo update

            # remove all port-forward from different namespaces
            ps aux | grep port-forw | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9 || true
          """
        }
      }
    }
    stage('Download Code') {
      steps {
        getVolthaCode([
          branch: "${release}",
          volthaSystemTestsChange: "${volthaSystemTestsChange}",
          volthaHelmChartsChange: "${volthaHelmChartsChange}",
        ])
      }
    }
    stage('Deploy common infrastructure') {
      // includes monitoring
      steps {
        sh '''
        if [ ${withMonitoring} = true ] ; then
          helm install -n infra nem-monitoring cord/nem-monitoring \
          -f $HOME/voltha-scale/grafana.yaml \
          --set prometheus.alertmanager.enabled=false,prometheus.pushgateway.enabled=false \
          --set kpi_exporter.enabled=false,dashboards.xos=false,dashboards.onos=false,dashboards.aaa=false,dashboards.voltha=false
        fi
        '''
      }
    }
    stage('Deploy VOLTHA infrastructure') {
      steps {
        script {
          def localCharts = false
          if (volthaHelmChartsChange != "") {
            localCharts = true
          }

          def infraHelmFlags =
            " --set global.log_level=${logLevel} " +
            "--set onos-classic.onosSshPort=30115 " +
            "--set onos-classic.onosApiPort=30120 " +
            "--set etcd.enabled=false " +
            " ${params.extraHelmFlags} "

          volthaInfraDeploy([
            workflow: workflow,
            infraNamespace: "infra",
            extraHelmFlags: infraHelmFlags,
            localCharts: localCharts,
            onosReplica: onosReplicas,
            atomixReplica: atomixReplicas,
            kafkaReplica: kafkaReplicas,
            etcdReplica: etcdReplicas,
          ])
        }
      }
    }
    stage('Deploy Voltha') {
      steps {
        deploy_voltha_stacks(params.volthaStacks)
      }
    }
    stage('Start logging') {
      //FIXME this collects the logs all in one file for all the 10 stacks
      steps {
        sh returnStdout: false, script: '''
        # start logging with kail

        mkdir -p $LOG_FOLDER

        list=($APPS_TO_LOG)
        for app in "${list[@]}"
        do
          echo "Starting logs for: ${app}"
          _TAG=kail-$app kail -l app=$app --since 1h > $LOG_FOLDER/$app.log&
        done
        '''
      }
    }
    stage('Configuration') {
      steps {
        script {
          sh returnStdout: false, script: """

          # forward ETCD port
          JENKINS_NODE_COOKIE="dontKillMe" _TAG=etcd-port-forward /bin/bash -c "while true; do kubectl -n infra port-forward --address 0.0.0.0 service/etcd 9999:2379; done 2>&1 " &

          # forward ONOS ports
          JENKINS_NODE_COOKIE="dontKillMe" _TAG=onos-port-forward /bin/bash -c "while true; do kubectl -n infra port-forward --address 0.0.0.0 service/voltha-infra-onos-classic-hs 8101:8101; done 2>&1 " &
          JENKINS_NODE_COOKIE="dontKillMe" _TAG=onos-port-forward /bin/bash -c "while true; do kubectl -n infra port-forward --address 0.0.0.0 service/voltha-infra-onos-classic-hs 8181:8181; done 2>&1 " &

          # make sure the the port-forward has started before moving forward
          sleep 5
          """
          sh returnStdout: false, script: """
          # TODO this needs to be repeated per stack
          # kubectl exec \$(kubectl get pods | grep -E "bbsim[0-9]" | awk 'NR==1{print \$1}') -- bbsimctl log ${logLevel.toLowerCase()} false

          #Setting link discovery
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.provider.lldp.impl.LldpLinkProvider enabled ${withLLDP}
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.net.flow.impl.FlowRuleManager allowExtraneousRules true
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.net.flow.impl.FlowRuleManager importExtraneousRules true
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.net.flowobjective.impl.InOrderFlowObjectiveManager accumulatorMaxBatchMillis 900
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.net.flowobjective.impl.InOrderFlowObjectiveManager accumulatorMaxIdleMillis 500
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.opencord.olt.impl.Olt provisionDelay 1000

          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg log:set ${logLevel} org.onosproject
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg log:set ${logLevel} org.opencord

          # Set Flows/Ports/Meters poll frequency
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.provider.of.flow.impl.OpenFlowRuleProvider flowPollFrequency ${onosStatInterval}
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.provider.of.device.impl.OpenFlowDeviceProvider portStatsPollFrequency ${onosStatInterval}

          if [ ${withFlows} = false ]; then
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 app deactivate org.opencord.olt
          fi
          """
        }
      }
    }
    stage('Setup Test') {
      steps {
        sh '''
          mkdir -p $WORKSPACE/RobotLogs
          cd $WORKSPACE/voltha-system-tests
          make vst_venv
        '''
      }
    }
    stage('Run Test') {
      steps {
        test_voltha_stacks(params.volthaStacks)
      }
    }
  }
  post {
    always {
      // collect result, done in the "post" step so it's executed even in the
      // event of a timeout in the tests
      sh '''

        # stop the kail processes
        list=($APPS_TO_LOG)
        for app in "${list[@]}"
        do
          echo "Stopping logs for: ${app}"
          _TAG="kail-$app"
          P_IDS="$(ps e -ww -A | grep "_TAG=$_TAG" | grep -v grep | awk '{print $1}')"
          if [ -n "$P_IDS" ]; then
            echo $P_IDS
            for P_ID in $P_IDS; do
              kill -9 $P_ID
            done
          fi
        done
      '''
      // compressing the logs to save space on Jenkins
      sh '''
      cd $LOG_FOLDER
      tar -czf logs.tar.gz *.log
      rm *.log
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
        group: 'Voltha-Scale-Numbers', numBuilds: '20', style: 'line', title: "Scale Test (Stacks: ${params.volthaStacks}, OLTs: ${olts}, PONs: ${pons}, ONUs: ${onus})", yaxis: 'Time (s)', useDescr: true
      ])
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: 'RobotLogs/**/log.html',
        otherFiles: '',
        outputFileName: 'RobotLogs/**/output.xml',
        outputPath: '.',
        passThreshold: 100,
        reportFileName: 'RobotLogs/**/report.html',
        unstableThreshold: 0]);
      // get all the logs from kubernetes PODs
      sh returnStdout: false, script: '''

        # store information on running charts
        helm ls --all-namespaces > $LOG_FOLDER/helm-list.txt || true

        # store information on the running pods
        kubectl get pods --all-namespaces -o wide > $LOG_FOLDER/pods.txt || true
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq | tee $LOG_FOLDER/pod-images.txt || true
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq | tee $LOG_FOLDER/pod-imagesId.txt || true

        # copy the ONOS logs directly from the container to avoid the color codes
        printf '%s\n' $(kubectl get pods -n infra -l app=onos-classic -o=jsonpath="{.items[*]['metadata.name']}") | xargs --no-run-if-empty -I# bash -c "kubectl cp #:${karafHome}/data/log/karaf.log $LOG_FOLDER/#.log" || true

      '''
      // dump all the BBSim(s) ONU information
      script {
        for (int i = 1; i <= params.volthaStacks.toInteger(); i++) {
          stack_ns="voltha"+i
          sh """
          mkdir -p \$LOG_FOLDER/${stack_ns}
          BBSIM_IDS=\$(kubectl -n ${stack_ns} get pods | grep bbsim | grep -v server | awk '{print \$1}')
          IDS=(\$BBSIM_IDS)

          for bbsim in "\${IDS[@]}"
          do
            kubectl -n ${stack_ns} exec -t \$bbsim -- bbsimctl onu list > \$LOG_FOLDER/${stack_ns}/\$bbsim-device-list.txt || true
            kubectl -n ${stack_ns} exec -t \$bbsim -- bbsimctl service list > \$LOG_FOLDER/${stack_ns}/\$bbsim-service-list.txt || true
            kubectl -n ${stack_ns} exec -t \$bbsim -- bbsimctl olt resources GEM_PORT > \$LOG_FOLDER/${stack_ns}/\$bbsim-flows-gem-ports.txt || true
            kubectl -n ${stack_ns} exec -t \$bbsim -- bbsimctl olt resources ALLOC_ID > \$LOG_FOLDER/${stack_ns}/\$bbsim-flows-alloc-ids.txt || true
            kubectl -n ${stack_ns} exec -t \$bbsim -- bbsimctl olt pons > \$LOG_FOLDER/${stack_ns}/\$bbsim-pon-resources.txt || true
          done
          """
        }
      }
      // get ONOS debug infos
      sh '''

        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 apps -a -s > $LOG_FOLDER/onos-apps.txt || true
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 nodes > $LOG_FOLDER/onos-nodes.txt || true
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 masters > $LOG_FOLDER/onos-masters.txt || true
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 roles > $LOG_FOLDER/onos-roles.txt || true

        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 ports > $LOG_FOLDER/onos-ports-list.txt || true
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 hosts > $LOG_FOLDER/onos-hosts-list.txt || true

        if [ ${withFlows} = true ] ; then
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 volt-olts > $LOG_FOLDER/onos-olt-list.txt || true
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 flows -s > $LOG_FOLDER/onos-flows-list.txt || true
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 meters > $LOG_FOLDER/onos-meters-list.txt || true
        fi

        if [ ${provisionSubscribers} = true ]; then
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 volt-programmed-subscribers > $LOG_FOLDER/onos-programmed-subscribers.txt || true
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 volt-programmed-meters > $LOG_FOLDER/onos-programmed-meters.txt || true
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 volt-bpmeter-mappings > $LOG_FOLDER/onos-bpmeter-mappings.txt || true
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 volt-failed-subscribers > $LOG_FOLDER/onos-failed-subscribers.txt || true
        fi

        if [ ${withEapol} = true ] ; then
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 aaa-users > $LOG_FOLDER/onos-aaa-users.txt || true
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 aaa-statistics > $LOG_FOLDER/onos-aaa-statistics.txt || true
        fi

        if [ ${withDhcp} = true ] ; then
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 dhcpl2relay-allocations > $LOG_FOLDER/onos-dhcp-allocations.txt || true
        fi
      '''
      // collect etcd metrics
      sh '''
        mkdir -p $WORKSPACE/etcd-metrics
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_debugging_mvcc_keys_total' | jq '.data' > $WORKSPACE/etcd-metrics/etcd-key-count.json || true
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=grpc_server_handled_total{grpc_service="etcdserverpb.KV"}' | jq '.data' > $WORKSPACE/etcd-metrics/etcd-rpc-count.json || true
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_debugging_mvcc_db_total_size_in_bytes' | jq '.data' > $WORKSPACE/etcd-metrics/etcd-db-size.json || true
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_disk_backend_commit_duration_seconds_sum' | jq '.data'  > $WORKSPACE/etcd-metrics/etcd-backend-write-time.json || true
      '''
      // get VOLTHA debug infos
      script {
        for (int i = 1; i <= params.volthaStacks.toInteger(); i++) {
          stack_ns="voltha"+i
          voltcfg="~/.volt/config-voltha"+i
          try {
            sh """

            # _TAG=voltha-port-forward kubectl port-forward --address 0.0.0.0 -n voltha${i} svc/voltha${i}-voltha-api 55555:55555& > /dev/null 2>&1
            _TAG="voltha-port-forward" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n voltha${i} svc/voltha${i}-voltha-api 55555:55555 > /dev/null 2>&1; done"&

            voltctl -m 8MB device list -o json > $LOG_FOLDER/${stack_ns}/device-list.json || true
            python -m json.tool $LOG_FOLDER/${stack_ns}/device-list.json > $LOG_FOLDER/${stack_ns}/voltha-devices-list.json || true
            rm $LOG_FOLDER/${stack_ns}/device-list.json || true
            voltctl -m 8MB device list > $LOG_FOLDER/${stack_ns}/voltha-devices-list.txt || true

            DEVICE_LIST=
            printf '%s\n' \$(voltctl -m 8MB device list | grep olt | awk '{print \$1}') | xargs --no-run-if-empty -I# bash -c "voltctl-m 8MB device flows # > $LOG_FOLDER/${stack_ns}/voltha-device-flows-#.txt" || true
            printf '%s\n' \$(voltctl -m 8MB device list | grep olt | awk '{print \$1}') | xargs --no-run-if-empty -I# bash -c "voltctl -m 8MB device port list --format 'table{{.PortNo}}\t{{.Label}}\t{{.Type}}\t{{.AdminState}}\t{{.OperStatus}}' # > $LOG_FOLDER/${stack_ns}/voltha-device-ports-#.txt" || true

            printf '%s\n' \$(voltctl -m 8MB logicaldevice list -q) | xargs --no-run-if-empty -I# bash -c "voltctl -m 8MB logicaldevice flows # > $LOG_FOLDER/${stack_ns}/voltha-logicaldevice-flows-#.txt" || true
            printf '%s\n' \$(voltctl -m 8MB logicaldevice list -q) | xargs --no-run-if-empty -I# bash -c "voltctl -m 8MB logicaldevice port list # > $LOG_FOLDER/${stack_ns}/voltha-logicaldevice-ports-#.txt" || true

            # remove VOLTHA port-forward
            ps aux | grep port-forw | grep voltha-api | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9 || true
            """
          } catch(e) {
            println e
            sh '''
            echo "Can't get device list from voltctl"
            '''
          }
        }
      }
      // get cpu usage by container
      sh '''
      if [ ${withMonitoring} = true ] ; then
        cd $WORKSPACE/voltha-system-tests
        source ./vst_venv/bin/activate
        sleep 60 # we have to wait for prometheus to collect all the information
        python tests/scale/sizing.py -o $WORKSPACE/plots || true
      fi
      '''
      archiveArtifacts artifacts: 'kind-voltha/install-*.log,execution-time-*.txt,logs/**/*.txt,logs/**/*.tar.gz,RobotLogs/**/*,plots/*,etcd-metrics/*'
    }
  }
}

def deploy_voltha_stacks(numberOfStacks) {
  for (int i = 1; i <= numberOfStacks.toInteger(); i++) {
    stage("Deploy VOLTHA stack " + i) {
      def extraHelmFlags = "${params.extraHelmFlags} --set global.log_level=${logLevel},enablePerf=true,onu=${onus},pon=${pons} "
      extraHelmFlags += " --set securityContext.enabled=false "

      // temporary for Redis patch
      extraHelmFlags += " -f $WORKSPACE/voltha-helm-charts/voltha-stack/values-redis.yaml "

      // override BBSim to use the public one
      extraHelmFlags += " --set images.bbsim.repository=voltha/bbsim,images.bbsim.tag=master "

      def volthaHelmFlags = extraHelmFlags +
        ofAgentConnections(onosReplicas.toInteger(), "voltha-infra", "infra")

      volthaHelmFlags += " --set voltha.ingress.enabled=true --set voltha.ingress.enableVirtualHosts=true --set voltha.fullHostnameOverride=voltha${i}.scale2.dev "

      def localCharts = false
      if (volthaHelmChartsChange != "") {
        localCharts = true
      }

      volthaStackDeploy([
        bbsimReplica: olts.toInteger(),
        infraNamespace: "infra",
        volthaNamespace: "voltha${i}",
        stackName: "voltha${i}",
        stackId: i,
        workflow: workflow,
        extraHelmFlags: volthaHelmFlags,
        localCharts: localCharts,
      ])
    }
  }
}

def test_voltha_stacks(numberOfStacks) {
  for (int i = 1; i <= numberOfStacks.toInteger(); i++) {
    stage("Test VOLTHA stack " + i) {
      timeout(time: 15, unit: 'MINUTES') {
        sh """

        # we are restarting the voltha-api port-forward for each stack, no need to have a different voltconfig file
        voltctl -s 127.0.0.1:55555 config > $HOME/.volt/config
        export VOLTCONFIG=$HOME/.volt/config

        # _TAG=voltha-port-forward kubectl port-forward --address 0.0.0.0 -n voltha${i} svc/voltha${i}-voltha-api 55555:55555& > /dev/null 2>&1
        _TAG="voltha-port-forward" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n voltha${i} svc/voltha${i}-voltha-api 55555:55555 > /dev/null 2>&1; done"&


          ROBOT_PARAMS="-v stackId:${i} \
            -v olt:${olts} \
            -v pon:${pons} \
            -v onu:${onus} \
            -v workflow:${workflow} \
            -v withEapol:${withEapol} \
            -v withDhcp:${withDhcp} \
            -v withIgmp:${withIgmp} \
            --noncritical non-critical \
            -e igmp \
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
          robot -d $WORKSPACE/RobotLogs/voltha${i} \
          \$ROBOT_PARAMS tests/scale/Voltha_Scale_Tests.robot

          # collect results
          python tests/scale/collect-result.py -r $WORKSPACE/RobotLogs/voltha${i}/output.xml -p $WORKSPACE/plots > $WORKSPACE/execution-time-voltha${i}.txt || true
          cat $WORKSPACE/execution-time-voltha${i}.txt
        """
        sh """
          # remove VOLTHA port-forward
          ps aux | grep port-forw | grep voltha-api | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9 2>&1 > /dev/null || true
        """
      }
    }
  }
}
