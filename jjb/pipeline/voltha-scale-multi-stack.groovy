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
      timeout(time: 120, unit: 'MINUTES')
  }
  environment {
    JENKINS_NODE_COOKIE="dontKillMe" // do not kill processes after the build is done
    KUBECONFIG="$HOME/.kube/config"
    SSHPASS="karaf"
    PATH="$PATH:$WORKSPACE/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    SCHEDULE_ON_CONTROL_NODES="yes"
    FANCY=0
    WAIT_ON_DOWN="yes"
    WITH_SIM_ADAPTERS="no"
    WITH_RADIUS="${withRadius}"
    WITH_BBSIM="yes"
    LEGACY_BBSIM_INDEX="no"
    DEPLOY_K8S="no"
    CONFIG_SADIS="external"
    WITH_KAFKA="yes"
    WITH_ETCD="yes"
    VOLTHA_ETCD_PORT=9999
    INFRA_NS="infra"

    // configurable options
    WITH_EAPOL="${withEapol}"
    WITH_DHCP="${withDhcp}"
    WITH_IGMP="${withIgmp}"
    VOLTHA_LOG_LEVEL="${logLevel}"
    NUM_OF_BBSIM="${olts}"
    NUM_OF_OPENONU="${openonuAdapterReplicas}"
    NUM_OF_ONOS="${onosReplicas}"
    NUM_OF_ATOMIX="${atomixReplicas}"
    NUM_OF_KAFKA="${kafkaReplicas}"
    NUM_OF_ETCD="${etcdReplicas}"
    WITH_PPROF="${withProfiling}"
    EXTRA_HELM_FLAGS="${extraHelmFlags} " // note that the trailing space is required to separate the parameters from appends done later
    VOLTHA_CHART="${volthaChart}"
    VOLTHA_BBSIM_CHART="${bbsimChart}"
    VOLTHA_ADAPTER_OPEN_OLT_CHART="${openoltAdapterChart}"
    VOLTHA_ADAPTER_OPEN_ONU_CHART="${openonuAdapterChart}"
    ONOS_CLASSIC_CHART="${onosChart}"
    RADIUS_CHART="${radiusChart}"

    APPS_TO_LOG="etcd kafka onos-onos-classic adapter-open-onu adapter-open-olt rw-core ofagent bbsim radius bbsim-sadis-server"
    LOG_FOLDER="$WORKSPACE/logs"

    GERRIT_PROJECT="${GERRIT_PROJECT}"
  }

  stages {
    stage ('Cleanup') {
      steps {
        timeout(time: 11, unit: 'MINUTES') {
          sh returnStdout: false, script: """
            helm repo add stable https://charts.helm.sh/stable
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

            NAMESPACES="voltha1 voltha2 infra default"
            for NS in \$NAMESPACES
            do
                for hchart in \$(helm list -n \$NS -q | grep -E -v 'docker-registry|kafkacat');
                do
                    echo "Purging chart: \${hchart}"
                    helm delete -n \$NS "\${hchart}"
                done
            done

            test -e $WORKSPACE/kind-voltha/voltha && cd $WORKSPACE/kind-voltha && ./voltha down

            cd $WORKSPACE
            rm -rf $WORKSPACE/*

            # remove orphaned port-forward from different namespaces
            ps aux | grep port-forw | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9
          """
        }
      }
    }
    stage('Clone kind-voltha') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/kind-voltha",
            refspec: "${kindVolthaChange}"
          ]],
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
            git fetch https://gerrit.opencord.org/kind-voltha ${kindVolthaChange} && git checkout FETCH_HEAD
          fi
          """)
        }
      }
    }
    stage('Clone voltha-system-tests') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/voltha-system-tests",
            refspec: "${volthaSystemTestsChange}"
          ]],
          branches: [[ name: "${release}", ]],
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
    stage('Build patch') {
      when {
        expression {
          return params.GERRIT_PROJECT
        }
      }
      steps {
        sh """
        git clone https://\$GERRIT_HOST/\$GERRIT_PROJECT
        cd \$GERRIT_PROJECT
        git fetch https://\$GERRIT_HOST/\$GERRIT_PROJECT \$GERRIT_REFSPEC && git checkout FETCH_HEAD

        DOCKER_REGISTRY=${dockerRegistry}/ DOCKER_REPOSITORY=voltha/ DOCKER_TAG=voltha-scale make docker-build
        DOCKER_REGISTRY=${dockerRegistry}/ DOCKER_REPOSITORY=voltha/ DOCKER_TAG=voltha-scale make docker-push
        """
      }
    }
    stage('Deploy common infrastructure') {
      // includes monitoring, kafka, etcd
      steps {
        sh '''
        if [ ${withMonitoring} = true ] ; then
          helm install -n $INFRA_NS nem-monitoring cord/nem-monitoring \
          -f $HOME/voltha-scale/grafana.yaml \
          --set prometheus.alertmanager.enabled=false,prometheus.pushgateway.enabled=false \
          --set kpi_exporter.enabled=false,dashboards.xos=false,dashboards.onos=false,dashboards.aaa=false,dashboards.voltha=false
        fi
        '''
      }
    }
    stage('Deploy VOLTHA infrastructure') {
      steps {
        sh returnStdout: false, script: '''

          cd $WORKSPACE/kind-voltha/

          export ETCD_CHART=$HOME/teone/helm-charts/etcd
          export KAFKA_CHART=$HOME/teone/helm-charts/kafka

          # KAFKA config
          export NUM_OF_KAFKA=${kafkaReplicas}
          export EXTRA_HELM_FLAGS+=' --set prometheus.kafka.enabled=true,prometheus.operator.enabled=true,prometheus.jmx.enabled=true,prometheus.operator.serviceMonitor.namespace=default '

          # ETCD config
          export EXTRA_HELM_FLAGS+=" --set memoryMode=${inMemoryEtcdStorage} "

          NAME=infra JUST_INFRA=y ./voltha up

          # Forward the ETCD port onto $VOLTHA_ETCD_PORT
          _TAG=etcd-port-forward kubectl -n \$INFRA_NS port-forward --address 0.0.0.0 -n default service/etcd $VOLTHA_ETCD_PORT:2379&
        '''
      }
    }
    stage('Deploy Voltha') {
      steps {
        deploy_voltha_stacks(params.volthaStacks)
      }
    }
    stage('Start logging') {
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

          # TODO this needs to be repeated per stack
          # kubectl exec \$(kubectl get pods | grep -E "bbsim[0-9]" | awk 'NR==1{print \$1}') -- bbsimctl log ${logLevel.toLowerCase()} false

          #Setting link discovery
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.provider.lldp.impl.LldpLinkProvider enabled ${withLLDP}
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.net.flow.impl.FlowRuleManager allowExtraneousRules true
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.net.flow.impl.FlowRuleManager importExtraneousRules true
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.net.flowobjective.impl.InOrderFlowObjectiveManager accumulatorMaxBatchMillis 1000
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.net.flowobjective.impl.InOrderFlowObjectiveManager accumulatorMaxIdleMillis 500

          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg log:set ${logLevel} org.onosproject
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg log:set ${logLevel} org.opencord

          # Set Flows/Ports/Meters poll frequency
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.provider.of.flow.impl.OpenFlowRuleProvider flowPollFrequency ${onosStatInterval}
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.provider.of.device.impl.OpenFlowDeviceProvider portStatsPollFrequency ${onosStatInterval}

          if [ ${withFlows} = false ]; then
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 app deactivate org.opencord.olt
          fi

          if [ ${withPcap} = true ] && [ ${volthaStacks} -eq 1 ] ; then
            # Start the tcp-dump in ofagent
            export OF_AGENT=\$(kubectl -n \$INFRA_NS get pods -l app=ofagent -o name)
            kubectl exec \$OF_AGENT -- apk update
            kubectl exec \$OF_AGENT -- apk add tcpdump
            kubectl exec \$OF_AGENT -- mv /usr/sbin/tcpdump /usr/bin/tcpdump
            _TAG=ofagent-tcpdump kubectl -n \$INFRA_NS exec \$OF_AGENT -- tcpdump -nei eth0 -w out.pcap&

            # Start the tcp-dump in radius
            export RADIUS=\$(kubectl -n \$INFRA_NS get pods -l app=radius -o name)
            kubectl exec \$RADIUS -- apt-get update
            kubectl exec \$RADIUS -- apt-get install -y tcpdump
            _TAG=radius-tcpdump kubectl -n \$INFRA_NS exec \$RADIUS -- tcpdump -w out.pcap&

            # Start the tcp-dump in ONOS
            for i in \$(seq 0 \$ONOSES); do
              INSTANCE="onos-onos-classic-\$i"
              kubectl exec \$INSTANCE -- apt-get update
              kubectl exec \$INSTANCE -- apt-get install -y tcpdump
              kubectl exec \$INSTANCE -- mv /usr/sbin/tcpdump /usr/bin/tcpdump
              _TAG=\$INSTANCE kubectl -n \$INFRA_NS exec \$INSTANCE -- /usr/bin/tcpdump -nei eth0 port 1812 -w out.pcap&
            done
          else
            echo "PCAP not supported for multiple VOLTHA stacks"
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
        sh '''
          if [ ${withProfiling} = true ] && [ ${volthaStacks} -eq 1 ]; then
            mkdir -p $LOG_FOLDER/pprof
            cat << EOF > $WORKSPACE/pprof.sh
timestamp() {
  date +"%T"
}

i=0
while [[ true ]]; do
  ((i++))
  ts=$(timestamp)
  go tool pprof -png http://127.0.0.1:6060/debug/pprof/heap > $LOG_FOLDER/pprof/rw-core-heap-\\$i-\\$ts.png
  go tool pprof -png http://127.0.0.1:6060/debug/pprof/goroutine > $LOG_FOLDER/pprof/rw-core-goroutine-\\$i-\\$ts.png
  curl -o $LOG_FOLDER/pprof/rw-core-profile-\\$i-\\$ts.pprof http://127.0.0.1:6060/debug/pprof/profile?seconds=10
  go tool pprof -png $LOG_FOLDER/pprof/rw-core-profile-\\$i-\\$ts.pprof > $LOG_FOLDER/pprof/rw-core-profile-\\$i-\\$ts.png

  go tool pprof -png http://127.0.0.1:6061/debug/pprof/heap > $LOG_FOLDER/pprof/openolt-heap-\\$i-\\$ts.png
  go tool pprof -png http://127.0.0.1:6061/debug/pprof/goroutine > $LOG_FOLDER/pprof/openolt-goroutine-\\$i-\\$ts.png
  curl -o $LOG_FOLDER/pprof/openolt-profile-\\$i-\\$ts.pprof http://127.0.0.1:6061/debug/pprof/profile?seconds=10
  go tool pprof -png $LOG_FOLDER/pprof/openolt-profile-\\$i-\\$ts.pprof > $LOG_FOLDER/pprof/openolt-profile-\\$i-\\$ts.png

  go tool pprof -png http://127.0.0.1:6062/debug/pprof/heap > $LOG_FOLDER/pprof/ofagent-heap-\\$i-\\$ts.png
  go tool pprof -png http://127.0.0.1:6062/debug/pprof/goroutine > $LOG_FOLDER/pprof/ofagent-goroutine-\\$i-\\$ts.png
  curl -o $LOG_FOLDER/pprof/ofagent-profile-\\$i-\\$ts.pprof http://127.0.0.1:6062/debug/pprof/profile?seconds=10
  go tool pprof -png $LOG_FOLDER/pprof/ofagent-profile-\\$i-\\$ts.pprof > $LOG_FOLDER/pprof/ofagent-profile-\\$i-\\$ts.png

  sleep 10
done
EOF

            _TAG="pprof"
            _TAG=$_TAG bash $WORKSPACE/pprof.sh &
          else
            echo "Profiling not supported for multiple VOLTHA stacks"
          fi
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

        if [ ${withPcap} = true ] && [ ${volthaStacks} -eq 1 ]; then
          # stop ofAgent tcpdump
          P_ID="\$(ps e -ww -A | grep "_TAG=ofagent-tcpdump" | grep -v grep | awk '{print \$1}')"
          if [ -n "\$P_ID" ]; then
            kill -9 \$P_ID
          fi

          # stop radius tcpdump
          P_ID="\$(ps e -ww -A | grep "_TAG=radius-tcpdump" | grep -v grep | awk '{print \$1}')"
          if [ -n "\$P_ID" ]; then
            kill -9 \$P_ID
          fi

          # stop onos tcpdump
          LIMIT=$(($NUM_OF_ONOS - 1))
          for i in $(seq 0 $LIMIT); do
            INSTANCE="onos-onos-classic-$i"
            P_ID="\$(ps e -ww -A | grep "_TAG=$INSTANCE" | grep -v grep | awk '{print \$1}')"
            if [ -n "\$P_ID" ]; then
              kill -9 \$P_ID
            fi
          done

          # copy the file
          export OF_AGENT=$(kubectl get pods -l app=ofagent | awk 'NR==2{print $1}') || true
          kubectl cp $OF_AGENT:out.pcap $LOG_FOLDER/ofagent.pcap || true

          export RADIUS=$(kubectl get pods -l app=radius | awk 'NR==2{print $1}') || true
          kubectl cp $RADIUS:out.pcap $LOG_FOLDER/radius.pcap || true

          LIMIT=$(($NUM_OF_ONOS - 1))
          for i in $(seq 0 $LIMIT); do
            INSTANCE="onos-onos-classic-$i"
            kubectl cp $INSTANCE:out.pcap $LOG_FOLDER/$INSTANCE.pcap || true
          done
        fi
      '''
      sh '''
        if [ ${withProfiling} = true ] && [ ${volthaStacks} -eq 1 ]; then
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
        printf '%s\n' $(kubectl -n \$INFRA_NS get pods -l app=onos-onos-classic -o=jsonpath="{.items[*]['metadata.name']}") | xargs --no-run-if-empty -I# bash -c "kubectl -n \$INFRA_NS cp #:${karafHome}/data/log/karaf.log $LOG_FOLDER/#.log" || true

        # get radius logs out of the container
        kubectl -n \$INFRA_NS  cp $(kubectl -n \$INFRA_NS get pods -l app=radius --no-headers  | awk '{print $1}'):/var/log/freeradius/radius.log $LOG_FOLDER//radius.log || true
      '''
      // dump all the BBSim(s) ONU information
      script {
        for (int i = 1; i <= params.volthaStacks.toInteger(); i++) {
          stack_ns="voltha"+i
          sh """
          BBSIM_IDS=\$(kubectl -n ${stack_ns} get pods | grep bbsim | grep -v server | awk '{print \$1}')
          IDS=(\$BBSIM_IDS)

          for bbsim in "\${IDS[@]}"
          do
            kubectl -n ${stack_ns} exec -t \$bbsim -- bbsimctl onu list > $LOG_FOLDER/${stack_ns}/\$bbsim-device-list.txt || true
            kubectl -n ${stack_ns} exec -t \$bbsim -- bbsimctl service list > $LOG_FOLDER/${stack_ns}/\$bbsim-service-list.txt || true
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
            voltctl -c $HOME/.volt/config-${stack_ns} -m 8MB device list -o json > $LOG_FOLDER/${stack_ns}/device-list.json || true
            python -m json.tool $LOG_FOLDER/${stack_ns}/device-list.json > $LOG_FOLDER/${stack_ns}/voltha-devices-list.json || true
            rm $LOG_FOLDER/${stack_ns}/device-list.json || true
            voltctl -c $HOME/.volt/config-${stack_ns} -m 8MB device list > $LOG_FOLDER/${stack_ns}/voltha-devices-list.txt || true

            DEVICE_LIST=
            printf '%s\n' \$(voltctl -c $HOME/.volt/config-${stack_ns} -m 8MB device list | grep olt | awk '{print \$1}') | xargs --no-run-if-empty -I# bash -c "voltctl -c $HOME/.volt/config-${stack_ns}-m 8MB device flows # > $LOG_FOLDER/${stack_ns}/voltha-device-flows-#.txt" || true
            printf '%s\n' \$(voltctl -c $HOME/.volt/config-${stack_ns} -m 8MB device list | grep olt | awk '{print \$1}') | xargs --no-run-if-empty -I# bash -c "voltctl -c $HOME/.volt/config-${stack_ns} -m 8MB device port list --format 'table{{.PortNo}}\t{{.Label}}\t{{.Type}}\t{{.AdminState}}\t{{.OperStatus}}' # > $LOG_FOLDER/${stack_ns}/voltha-device-ports-#.txt" || true

            printf '%s\n' \$(voltctl -c $HOME/.volt/config-${stack_ns} -m 8MB logicaldevice list -q) | xargs --no-run-if-empty -I# bash -c "voltctl -c $HOME/.volt/config-${stack_ns} -m 8MB logicaldevice flows # > $LOG_FOLDER/${stack_ns}/voltha-logicaldevice-flows-#.txt" || true
            printf '%s\n' \$(voltctl -c $HOME/.volt/config-${stack_ns} -m 8MB logicaldevice list -q) | xargs --no-run-if-empty -I# bash -c "voltctl -c $HOME/.volt/config-${stack_ns} -m 8MB logicaldevice port list # > $LOG_FOLDER/${stack_ns}/voltha-logicaldevice-ports-#.txt" || true
            """
          } catch(e) {
            sh '''
            echo "Can't get device list from voltclt"
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
      archiveArtifacts artifacts: 'kind-voltha/install-*.log,execution-time-*.txt,logs/**/*,RobotLogs/**/*,plots/*,etcd-metrics/*'
    }
  }
}

def deploy_voltha_stacks(numberOfStacks) {
  for (int i = 1; i <= numberOfStacks.toInteger(); i++) {
    stage("Deploy VOLTHA stack " + i) {
      sh returnStdout: false, script: """

      # unset voltha-api port so that the port is forwarded on a new one
      unset VOLTHA_API_PORT

      cd $WORKSPACE/kind-voltha/

      export NAME=voltha${i}
      export VOLTHA_NS=voltha${i}
      export ADAPTER_NS=voltha${i}
      export BBSIM_NS=voltha${i}
      export BBSIM_BASE_INDEX=${i}
      export WITH_ETCD=etcd.\$INFRA_NS.svc:2379
      export WITH_KAFKA=kafka.\$INFRA_NS.svc:9092
      export WITH_ONOS=onos-onos-classic-hs.\$INFRA_NS.svc:6653

      export EXTRA_HELM_FLAGS+=' '

      # Load the release defaults
      if [ '${release.trim()}' != 'master' ]; then
        source $WORKSPACE/kind-voltha/releases/${release}
        EXTRA_HELM_FLAGS+=" ${extraHelmFlags} "
      fi

      # BBSim custom image handling
      if [ '${bbsimImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'bbsim' ]; then
        IFS=: read -r bbsimRepo bbsimTag <<< '${bbsimImg.trim()}'
        EXTRA_HELM_FLAGS+="--set images.bbsim.repository=\$bbsimRepo,images.bbsim.tag=\$bbsimTag "
      fi

      # VOLTHA and ofAgent custom image handling
      # NOTE to override the rw-core image in a released version you must set the ofAgent image too
      # TODO split ofAgent and voltha-go
      if [ '${rwCoreImg.trim()}' != '' ] && [ '${ofAgentImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-go' ]; then
        IFS=: read -r rwCoreRepo rwCoreTag <<< '${rwCoreImg.trim()}'
        IFS=: read -r ofAgentRepo ofAgentTag <<< '${ofAgentImg.trim()}'
        EXTRA_HELM_FLAGS+="--set images.rw_core.repository=\$rwCoreRepo,images.rw_core.tag=\$rwCoreTag,images.ofagent.repository=\$ofAgentRepo,images.ofagent.tag=\$ofAgentTag "
      fi

      # OpenOLT custom image handling
      if [ '${openoltAdapterImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-openolt-adapter' ]; then
        IFS=: read -r openoltAdapterRepo openoltAdapterTag <<< '${openoltAdapterImg.trim()}'
        EXTRA_HELM_FLAGS+="--set images.adapter_open_olt.repository=\$openoltAdapterRepo,images.adapter_open_olt.tag=\$openoltAdapterTag "
      fi

      # OpenONU custom image handling
      if [ '${openonuAdapterImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-openonu-adapter' ]; then
        IFS=: read -r openonuAdapterRepo openonuAdapterTag <<< '${openonuAdapterImg.trim()}'
        EXTRA_HELM_FLAGS+="--set images.adapter_open_onu.repository=\$openonuAdapterRepo,images.adapter_open_onu.tag=\$openonuAdapterTag "
      fi

      # OpenONU Go custom image handling
      if [ '${openonuAdapterGoImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-openonu-adapter-go' ]; then
        IFS=: read -r openonuAdapterGoRepo openonuAdapterGoTag <<< '${openonuAdapterGoImg.trim()}'
        EXTRA_HELM_FLAGS+="--set images.adapter_open_onu_go.repository=\$openonuAdapterGoRepo,images.adapter_open_onu_go.tag=\$openonuAdapterGoTag "
      fi

      # ONOS custom image handling
      if [ '${onosImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-onos' ]; then
        IFS=: read -r onosRepo onosTag <<< '${onosImg.trim()}'
        EXTRA_HELM_FLAGS+="--set images.onos.repository=\$onosRepo,images.onos.tag=\$onosTag "
      fi

      # set BBSim parameters
      EXTRA_HELM_FLAGS+='--set enablePerf=true,pon=${pons},onu=${onus} '

      # disable the securityContext, this is a development cluster
      EXTRA_HELM_FLAGS+='--set securityContext.enabled=false '

      # No persistent-volume-claims in Atomix
      EXTRA_HELM_FLAGS+="--set atomix.persistence.enabled=false "

      echo "Installing with the following extra arguments:"
      echo $EXTRA_HELM_FLAGS

      # if it's newer than voltha-2.4 set the correct BBSIM_CFG
      if [ '${release.trim()}' != 'voltha-2.4' ]; then
        export BBSIM_CFG="$WORKSPACE/kind-voltha/configs/bbsim-sadis-${workflow}.yaml"
      fi

      # Use custom built images

      if [ '\$GERRIT_PROJECT' == 'voltha-go' ]; then
        EXTRA_HELM_FLAGS+="--set images.rw_core.repository=${dockerRegistry}/voltha/voltha-rw-core,images.rw_core.tag=voltha-scale "
      fi

      if [ '\$GERRIT_PROJECT' == 'voltha-openolt-adapter' ]; then
        EXTRA_HELM_FLAGS+="--set images.adapter_open_olt.repository=${dockerRegistry}/voltha/voltha-openolt-adapter,images.adapter_open_olt.tag=voltha-scale "
      fi

      if [ '\$GERRIT_PROJECT' == 'voltha-openonu-adapter' ]; then
        EXTRA_HELM_FLAGS+="--set images.adapter_open_onu.repository=${dockerRegistry}/voltha/voltha-openonu-adapter,images.adapter_open_onu.tag=voltha-scale "
      fi

      if [ '\$GERRIT_PROJECT' == 'voltha-openonu-adapter-go' ]; then
        EXTRA_HELM_FLAGS+="--set images.adapter_open_onu_go.repository=${dockerRegistry}/voltha/voltha-openonu-adapter-go,images.adapter_open_onu_go.tag=voltha-scale "
      fi

      if [ '\$GERRIT_PROJECT' == 'ofagent-go' ]; then
        EXTRA_HELM_FLAGS+="--set images.ofagent.repository=${dockerRegistry}/voltha/voltha-ofagent-go,images.ofagent.tag=voltha-scale "
      fi

      if [ '\$GERRIT_PROJECT' == 'voltha-onos' ]; then
        EXTRA_HELM_FLAGS+="--set images.onos.repository=${dockerRegistry}/voltha/voltha-onos,images.onos.tag=voltha-scale "
      fi

      if [ '\$GERRIT_PROJECT' == 'bbsim' ]; then
        EXTRA_HELM_FLAGS+="--set images.bbsim.repository=${dockerRegistry}/voltha/bbsim,images.bbsim.tag=voltha-scale "
      fi

      ./voltha up
      """
    }
  }
}

def test_voltha_stacks(numberOfStacks) {
  for (int i = 1; i <= numberOfStacks.toInteger(); i++) {
    stage("Test VOLTHA stack " + i) {
      timeout(time: 15, unit: 'MINUTES') {
        sh """
          export VOLTCONFIG="$HOME/.volt/config-voltha${i}"
          ROBOT_PARAMS="-v stackId:${i} \
            -v olt:${olts} \
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
          robot -d $WORKSPACE/RobotLogs/voltha${i} \
          \$ROBOT_PARAMS tests/scale/Voltha_Scale_Tests.robot

          # collect results
          python tests/scale/collect-result.py -r $WORKSPACE/RobotLogs/voltha${i}/output.xml -p $WORKSPACE/plots > $WORKSPACE/execution-time-voltha${i}.txt || true
          cat $WORKSPACE/execution-time-voltha${i}.txt
        """
      }
    }
  }
}
