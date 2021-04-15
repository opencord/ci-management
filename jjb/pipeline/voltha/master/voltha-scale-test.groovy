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

// deploy VOLTHA and performs a scale test

library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://gerrit.opencord.org/ci-management.git'
])

// this function generates the correct parameters for ofAgent
// to connect to multple ONOS instances
def ofAgentConnections(numOfOnos, releaseName, namespace) {
    def params = " "
    numOfOnos.times {
        params += "--set voltha.services.controller[${it}].address=${releaseName}-onos-classic-${it}.${releaseName}-onos-classic-hs.${namespace}.svc:6653 "
    }
    return params
}

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 60, unit: 'MINUTES')
  }
  environment {
    JENKINS_NODE_COOKIE="dontKillMe" // do not kill processes after the build is done
    KUBECONFIG="$HOME/.kube/config"
    VOLTCONFIG="$HOME/.volt/config"
    SSHPASS="karaf"
    VOLTHA_LOG_LEVEL="${logLevel}"
    NUM_OF_BBSIM="${olts}"
    NUM_OF_OPENONU="${openonuAdapterReplicas}"
    NUM_OF_ONOS="${onosReplicas}"
    NUM_OF_ATOMIX="${atomixReplicas}"
    EXTRA_HELM_FLAGS=" "

    APPS_TO_LOG="etcd kafka onos-classic adapter-open-onu adapter-open-olt rw-core ofagent bbsim radius bbsim-sadis-server onos-config-loader"
    LOG_FOLDER="$WORKSPACE/logs"

    GERRIT_PROJECT="${GERRIT_PROJECT}"
  }

  stages {
    stage ('Cleanup') {
      steps {
        timeout(time: 11, unit: 'MINUTES') {
          script {
            helmTeardown(["default"])
          }
          sh returnStdout: false, script: '''
            helm repo add onf https://charts.opencord.org
            helm repo update

            # remove orphaned port-forward from different namespaces
            ps aux | grep port-forw | grep -v grep | awk '{print $2}' | xargs --no-run-if-empty kill -9

            cd $WORKSPACE
            rm -rf $WORKSPACE/*
          '''
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
    stage('Clone voltha-helm-charts') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/voltha-helm-charts",
            refspec: "${volthaHelmChartsChange}"
          ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-helm-charts"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        script {
          sh(script:"""
            if [ '${volthaHelmChartsChange}' != '' ] ; then
              cd $WORKSPACE/voltha-helm-charts;
              git fetch https://gerrit.opencord.org/voltha-helm-charts ${volthaHelmChartsChange} && git checkout FETCH_HEAD
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
        helm install kafka $HOME/teone/helm-charts/kafka --set replicaCount=${kafkaReplicas},replicas=${kafkaReplicas} --set persistence.enabled=false \
          --set zookeeper.replicaCount=${kafkaReplicas} --set zookeeper.persistence.enabled=false \
          --set prometheus.kafka.enabled=true,prometheus.operator.enabled=true,prometheus.jmx.enabled=true,prometheus.operator.serviceMonitor.namespace=default

        # the ETCD chart use "auth" for resons different than BBsim, so strip that away
        ETCD_FLAGS=$(echo ${extraHelmFlags} | sed -e 's/--set auth=false / /g') | sed -e 's/--set auth=true / /g'
        ETCD_FLAGS+=" --set auth.rbac.enabled=false,persistence.enabled=false,statefulset.replicaCount=${etcdReplicas}"
        ETCD_FLAGS+=" --set memoryMode=${inMemoryEtcdStorage} "
        helm install --set replicas=${etcdReplicas} etcd $HOME/teone/helm-charts/etcd $ETCD_FLAGS

        if [ ${withMonitoring} = true ] ; then
          helm install nem-monitoring onf/nem-monitoring \
          -f $HOME/voltha-scale/grafana.yaml \
          --set prometheus.alertmanager.enabled=false,prometheus.pushgateway.enabled=false \
          --set kpi_exporter.enabled=false,dashboards.xos=false,dashboards.onos=false,dashboards.aaa=false,dashboards.voltha=false
        fi
        '''
      }
    }
    stage('Deploy Voltha') {
      steps {
        timeout(time: 10, unit: 'MINUTES') {
          script {
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
            def returned_flags = sh (returnStdout: true, script: """

              export EXTRA_HELM_FLAGS+=' '

              # BBSim custom image handling
              if [ '${bbsimImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'bbsim' ]; then
                IFS=: read -r bbsimRepo bbsimTag <<< '${bbsimImg.trim()}'
                EXTRA_HELM_FLAGS+="--set images.bbsim.repository=\$bbsimRepo,images.bbsim.tag=\$bbsimTag "
              fi

              # VOLTHA custom image handling
              if [ '${rwCoreImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-go' ]; then
                IFS=: read -r rwCoreRepo rwCoreTag <<< '${rwCoreImg.trim()}'
                EXTRA_HELM_FLAGS+="--set voltha.images.rw_core.repository=\$rwCoreRepo,voltha.images.rw_core.tag=\$rwCoreTag "
              fi

              # ofAgent custom image handling
              if [ '${ofAgentImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'of-agent' ]; then
                IFS=: read -r ofAgentRepo ofAgentTag <<< '${ofAgentImg.trim()}'
                EXTRA_HELM_FLAGS+="--set voltha.images.ofagent.repository=\$ofAgentRepo,voltha.images.ofagent.tag=\$ofAgentTag "
              fi

              # OpenOLT custom image handling
              if [ '${openoltAdapterImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-openolt-adapter' ]; then
                IFS=: read -r openoltAdapterRepo openoltAdapterTag <<< '${openoltAdapterImg.trim()}'
                EXTRA_HELM_FLAGS+="--set voltha-adapter-openolt.images.adapter_open_olt.repository=\$openoltAdapterRepo,voltha-adapter-openolt.images.adapter_open_olt.tag=\$openoltAdapterTag "
              fi

              # OpenONU custom image handling
              if [ '${openonuAdapterImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-openonu-adapter' ]; then
                IFS=: read -r openonuAdapterRepo openonuAdapterTag <<< '${openonuAdapterImg.trim()}'
                EXTRA_HELM_FLAGS+="--set voltha-adapter-openonu.images.adapter_open_onu.repository=\$openonuAdapterRepo,voltha-adapter-openonu.images.adapter_open_onu.tag=\$openonuAdapterTag "
              fi

              # OpenONU GO custom image handling
              if [ '${openonuAdapterGoImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-openonu-adapter-go' ]; then
                IFS=: read -r openonuAdapterGoRepo openonuAdapterGoTag <<< '${openonuAdapterGoImg.trim()}'
                EXTRA_HELM_FLAGS+="--set voltha-adapter-openonu.images.adapter_open_onu_go.repository=\$openonuAdapterGoRepo,voltha-adapter-openonu.images.adapter_open_onu_go.tag=\$openonuAdapterGoTag "
              fi

              # ONOS custom image handling
              if [ '${onosImg.trim()}' != '' ] && [ '\$GERRIT_PROJECT' != 'voltha-onos' ]; then
                IFS=: read -r onosRepo onosTag <<< '${onosImg.trim()}'
                EXTRA_HELM_FLAGS+="--set onos-classic.image.repository=\$onosRepo,onos-classic.image.tag=\$onosTag "
              fi

              # set BBSim parameters
              EXTRA_HELM_FLAGS+='--set enablePerf=true,pon=${pons},onu=${onus} '

              # disable the securityContext, this is a development cluster
              EXTRA_HELM_FLAGS+='--set securityContext.enabled=false '

              # No persistent-volume-claims in Atomix
              EXTRA_HELM_FLAGS+="--set onos-classic.atomix.persistence.enabled=false "

              # Use custom built images

              if [ '\$GERRIT_PROJECT' == 'voltha-go' ]; then
                EXTRA_HELM_FLAGS+="--set voltha.images.rw_core.repository=${dockerRegistry}/voltha/voltha-rw-core,voltha.images.rw_core.tag=voltha-scale "
              fi

              if [ '\$GERRIT_PROJECT' == 'voltha-openolt-adapter' ]; then
                EXTRA_HELM_FLAGS+="--set voltha-openolt-adapter.images.adapter_open_olt.repository=${dockerRegistry}/voltha/voltha-openolt-adapter,voltha-openolt-adapter.images.adapter_open_olt.tag=voltha-scale "
              fi

              if [ '\$GERRIT_PROJECT' == 'voltha-openonu-adapter' ]; then
                EXTRA_HELM_FLAGS+="--set voltha-openonu-adapter.images.adapter_open_onu.repository=${dockerRegistry}/voltha/voltha-openonu-adapter,voltha-openonu-adapter.images.adapter_open_onu.tag=voltha-scale "
              fi

              if [ '\$GERRIT_PROJECT' == 'voltha-openonu-adapter-go' ]; then
                EXTRA_HELM_FLAGS+="--set voltha-openonu-adapter-go.images.adapter_open_onu_go.repository=${dockerRegistry}/voltha/voltha-openonu-adapter-go,voltha-openonu-adapter-go.images.adapter_open_onu_go.tag=voltha-scale "
              fi

              if [ '\$GERRIT_PROJECT' == 'ofagent-go' ]; then
                EXTRA_HELM_FLAGS+="--set voltha.images.ofagent.repository=${dockerRegistry}/voltha/voltha-ofagent-go,ofagent-go.images.ofagent.tag=voltha-scale "
              fi

              if [ '\$GERRIT_PROJECT' == 'voltha-onos' ]; then
                EXTRA_HELM_FLAGS+="--set onos-classic.image.repository=${dockerRegistry}/voltha/voltha-onos,onos-classic.image.tag=voltha-scale "
              fi

              if [ '\$GERRIT_PROJECT' == 'bbsim' ]; then
                EXTRA_HELM_FLAGS+="--set images.bbsim.repository=${dockerRegistry}/voltha/bbsim,images.bbsim.tag=voltha-scale "
              fi
              echo \$EXTRA_HELM_FLAGS

            """).trim()

            def extraHelmFlags = returned_flags
            // The added space before params.extraHelmFlags is required due to the .trim() above
            def infraHelmFlags =
              " --set etcd.enabled=false,kafka.enabled=false" +
              " --set global.log_level=${logLevel} " +
              "--set onos-classic.onosSshPort=30115 " +
              "--set onos-classic.onosApiPort=30120 " +
              extraHelmFlags + " " + params.extraHelmFlags

            println "Passing the following parameters to the VOLTHA infra deploy: ${infraHelmFlags}."

            volthaInfraDeploy([
              workflow: workflow,
              infraNamespace: "default",
              extraHelmFlags: infraHelmFlags,
              localCharts: false, // TODO support custom charts
              onosReplica: onosReplicas,
              atomixReplica: atomixReplicas,
            ])

            def stackHelmFlags = "${ofAgentConnections(onosReplicas.toInteger(), "voltha-infra", "default")} " +
                "--set voltha.services.kafka.adapter.address=kafka.default.svc:9092 " +
                "--set voltha.services.kafka.cluster.address=kafka.default.svc:9092 " +
                "--set voltha.services.etcd.address=etcd.default.svc:2379 " +
                "--set voltha-adapter-openolt.services.kafka.adapter.address=kafka.default.svc:9092 " +
                "--set voltha-adapter-openolt.services.kafka.cluster.address=kafka.default.svc:9092 " +
                "--set voltha-adapter-openolt.services.etcd.address=etcd.default.svc:2379 " +
                "--set voltha-adapter-openonu.services.kafka.adapter.address=kafka.default.svc:9092 " +
                "--set voltha-adapter-openonu.services.kafka.cluster.address=kafka.default.svc:9092 " +
                "--set voltha-adapter-openonu.services.etcd.address=etcd.default.svc:2379"

            stackHelmFlags += " --set onu=${onus},pon=${pons} --set global.log_level=${logLevel.toLowerCase()} "
            stackHelmFlags += " --set voltha.ingress.enabled=true --set voltha.ingress.enableVirtualHosts=true --set voltha.fullHostnameOverride=voltha.scale1.dev "
            stackHelmFlags += extraHelmFlags + " " + params.extraHelmFlags

            volthaStackDeploy([
              bbsimReplica: olts.toInteger(),
              infraNamespace: "default",
              volthaNamespace: "default",
              stackName: "voltha1", // TODO support custom charts
              workflow: workflow,
              extraHelmFlags: stackHelmFlags,
              localCharts: false,
            ])
            sh """
              set +x

              echo -ne "\nWaiting for VOLTHA and ONOS to start..."
              voltha=\$(kubectl get pods --all-namespaces -l app.kubernetes.io/part-of=voltha --no-headers | grep "0/" | wc -l)
              onos=\$(kubectl get pods --all-namespaces -l app=onos-classic --no-headers | grep "0/" | wc -l)
              while [[ \$voltha != 0 || \$onos != 0 ]]; do
                sleep 5
                echo -ne "."
                voltha=\$(kubectl get pods --all-namespaces -l app.kubernetes.io/part-of=voltha --no-headers | grep "0/" | wc -l)
                onos=\$(kubectl get pods --all-namespaces -l app=onos-classic --no-headers | grep "0/" | wc -l)
              done
              echo -ne "\nVOLTHA and ONOS pods ready\n"
              kubectl get pods --all-namespaces -l app.kubernetes.io/part-of=voltha --no-headers | grep "0/" | wc -l
              kubectl get pods --all-namespaces -l app=onos-classic --no-headers | grep "0/" | wc -l
            """
            start_port_forward(olts)
          }
        }
      }
    }
    stage('Configuration') {
      steps {
        script {
          def tech_prof_directory = "XGS-PON"
          sh returnStdout: false, script: """
          #Setting link discovery
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.provider.lldp.impl.LldpLinkProvider enabled ${withLLDP}

          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.net.flow.impl.FlowRuleManager allowExtraneousRules true
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.net.flow.impl.FlowRuleManager importExtraneousRules true


          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.net.flowobjective.impl.InOrderFlowObjectiveManager accumulatorMaxBatchMillis 900

          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.net.flowobjective.impl.InOrderFlowObjectiveManager accumulatorMaxIdleMillis 500

          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.opencord.olt.impl.Olt provisionDelay 1000

          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 log:set ${logLevel} org.onosproject
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 log:set ${logLevel} org.opencord

          # sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 log:set DEBUG org.opencord.cordmcast
          # sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 log:set DEBUG org.onosproject.mcast
          # sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 log:set DEBUG org.opencord.igmpproxy
          # sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 log:set DEBUG org.opencord.olt
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 log:set DEBUG org.onosproject.net.flowobjective.impl.FlowObjectiveManager

          kubectl exec \$(kubectl get pods | grep -E "bbsim[0-9]" | awk 'NR==1{print \$1}') -- bbsimctl log ${logLevel.toLowerCase()} false

          # Set Flows/Ports/Meters/Groups poll frequency
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.provider.of.flow.impl.OpenFlowRuleProvider flowPollFrequency ${onosStatInterval}
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.provider.of.device.impl.OpenFlowDeviceProvider portStatsPollFrequency ${onosStatInterval}
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.provider.of.group.impl.OpenFlowGroupProvider groupPollInterval ${onosGroupInterval}
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.net.flowobjective.impl.FlowObjectiveManager numThreads ${flowObjWorkerThreads}

          if [ ${withFlows} = false ]; then
            sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 app deactivate org.opencord.olt
          fi

          if [ '${workflow}' = 'tt' ]; then
            etcd_container=\$(kubectl get pods --all-namespaces | grep etcd | awk 'NR==1{print \$2}')
            kubectl cp $WORKSPACE/voltha-system-tests/tests/data/TechProfile-TT-HSIA.json \$etcd_container:/tmp/hsia.json
            put_result=\$(kubectl exec -it \$etcd_container -- /bin/sh -c 'cat /tmp/hsia.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/${tech_prof_directory}/64')
            kubectl cp $WORKSPACE/voltha-system-tests/tests/data/TechProfile-TT-VoIP.json \$etcd_container:/tmp/voip.json
            put_result=\$(kubectl exec -it \$etcd_container -- /bin/sh -c 'cat /tmp/voip.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/${tech_prof_directory}/65')
            kubectl cp $WORKSPACE/voltha-system-tests/tests/data/TechProfile-TT-MCAST.json \$etcd_container:/tmp/mcast.json
            put_result=\$(kubectl exec -it \$etcd_container -- /bin/sh -c 'cat /tmp/mcast.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/${tech_prof_directory}/66')
          fi

          if [ ${withPcap} = true ] ; then
            # Start the tcp-dump in ofagent
            export OF_AGENT=\$(kubectl get pods -l app=ofagent -o name)
            kubectl exec \$OF_AGENT -- apk update
            kubectl exec \$OF_AGENT -- apk add tcpdump
            kubectl exec \$OF_AGENT -- mv /usr/sbin/tcpdump /usr/bin/tcpdump
            _TAG=ofagent-tcpdump kubectl exec \$OF_AGENT -- tcpdump -nei eth0 -w out.pcap&

            # Start the tcp-dump in radius
            export RADIUS=\$(kubectl get pods -l app=radius -o name)
            kubectl exec \$RADIUS -- apt-get update
            kubectl exec \$RADIUS -- apt-get install -y tcpdump
            _TAG=radius-tcpdump kubectl exec \$RADIUS -- tcpdump -w out.pcap&

            # Start the tcp-dump in ONOS
            for i in \$(seq 0 \$ONOSES); do
              INSTANCE="onos-onos-classic-\$i"
              kubectl exec \$INSTANCE -- apt-get update
              kubectl exec \$INSTANCE -- apt-get install -y tcpdump
              kubectl exec \$INSTANCE -- mv /usr/sbin/tcpdump /usr/bin/tcpdump
              _TAG=\$INSTANCE kubectl exec \$INSTANCE -- /usr/bin/tcpdump -nei eth0 port 1812 -w out.pcap&
            done
          fi
          """
        }
      }
    }
    stage('Load MIB Template') {
      when {
        expression {
          return params.withMibTemplate
        }
      }
      steps {
        sh """
        # load MIB template
        wget https://raw.githubusercontent.com/opencord/voltha-openonu-adapter-go/master/templates/BBSM-12345123451234512345-00000000000001-v1.json
        cat BBSM-12345123451234512345-00000000000001-v1.json | kubectl exec -it \$(kubectl get pods |grep etcd | awk 'NR==1{print \$1}') -- etcdctl put service/voltha/omci_mibs/go_templates/BBSM/12345123451234512345/00000000000001
        """
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
            mkdir -p $LOG_FOLDER/pprof
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
          fi
        '''
        timeout(time: 15, unit: 'MINUTES') {
          sh '''
            ROBOT_PARAMS="--exitonfailure \
              -v olt:${olts} \
              -v pon:${pons} \
              -v onu:${onus} \
              -v ONOS_SSH_PORT:30115 \
              -v ONOS_REST_PORT:30120 \
              -v workflow:${workflow} \
              -v withEapol:${withEapol} \
              -v withDhcp:${withDhcp} \
              -v withIgmp:${withIgmp} \
              --noncritical non-critical \
              -e igmp -e teardown "

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
    stage('Run Igmp Tests') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/IgmpTests"
      }
      when {
        expression {
          return params.withIgmp
        }
      }
      steps {
        sh returnStdout: false, script: """
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 log:set DEBUG org.onosproject.store.group.impl
        """
        sh '''
          set +e
          mkdir -p $ROBOT_LOGS_DIR
          cd $WORKSPACE/voltha-system-tests
          make vst_venv
        '''
        timeout(time: 11, unit: 'MINUTES') {
          sh '''
            ROBOT_PARAMS="--exitonfailure \
              -v olt:${olts} \
              -v pon:${pons} \
              -v onu:${onus} \
              -v workflow:${workflow} \
              -v withEapol:${withEapol} \
              -v withDhcp:${withDhcp} \
              -v withIgmp:${withIgmp} \
              -v ONOS_SSH_PORT:30115 \
              -v ONOS_REST_PORT:30120 \
              --noncritical non-critical \
              -i igmp \
              -e setup -e activation -e flow-before \
              -e authentication -e provision -e flow-after \
              -e dhcp -e teardown "
            cd $WORKSPACE/voltha-system-tests
            source ./vst_venv/bin/activate
            robot -d $ROBOT_LOGS_DIR \
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

        if [ ${withPcap} = true ] ; then
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

        cd voltha-system-tests
        source ./vst_venv/bin/activate
        python tests/scale/collect-result.py -r $WORKSPACE/RobotLogs/output.xml -p $WORKSPACE/plots > $WORKSPACE/execution-time.txt || true
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
        logFileName: '**/log*.html',
        otherFiles: '',
        outputFileName: '**/output*.xml',
        outputPath: 'RobotLogs',
        passThreshold: 100,
        reportFileName: '**/report*.html',
        unstableThreshold: 0]);
      // get all the logs from kubernetes PODs
      sh returnStdout: false, script: '''

        # store information on running charts
        helm ls > $LOG_FOLDER/helm-list.txt || true

        # store information on the running pods
        kubectl get pods -o wide > $LOG_FOLDER/pods.txt || true
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq | tee $LOG_FOLDER/pod-images.txt || true
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq | tee $LOG_FOLDER/pod-imagesId.txt || true

        # copy the ONOS logs directly from the container to avoid the color codes
        printf '%s\n' $(kubectl get pods -l app=onos-classic -o=jsonpath="{.items[*]['metadata.name']}") | xargs --no-run-if-empty -I# bash -c "kubectl cp #:${karafHome}/data/log/karaf.log $LOG_FOLDER/#.log" || true

        # get ONOS cfg from the 3 nodes
        # kubectl exec -t voltha-infra-onos-classic-0 -- sh /root/onos/apache-karaf-4.2.9/bin/client cfg get > ~/voltha-infra-onos-classic-0-cfg.txt || true
        # kubectl exec -t voltha-infra-onos-classic-1 -- sh /root/onos/apache-karaf-4.2.9/bin/client cfg get > ~/voltha-infra-onos-classic-1-cfg.txt || true
        # kubectl exec -t voltha-infra-onos-classic-2 -- sh /root/onos/apache-karaf-4.2.9/bin/client cfg get > ~/voltha-infra-onos-classic-2-cfg.txt || true

        # kubectl exec -t voltha-infra-onos-classic-0 -- sh /root/onos/apache-karaf-4.2.9/bin/client obj-next-ids > ~/voltha-infra-onos-classic-0-next-objs.txt || true
        # kubectl exec -t voltha-infra-onos-classic-1 -- sh /root/onos/apache-karaf-4.2.9/bin/client obj-next-ids > ~/voltha-infra-onos-classic-1-next-objs.txt || true
        # kubectl exec -t voltha-infra-onos-classic-2 -- sh /root/onos/apache-karaf-4.2.9/bin/client obj-next-ids > ~/voltha-infra-onos-classic-2-next-objs.txt || true

        # get radius logs out of the container
        kubectl cp $(kubectl get pods -l app=radius --no-headers  | awk '{print $1}'):/var/log/freeradius/radius.log $LOG_FOLDER/radius.log || true
      '''
      // dump all the BBSim(s) ONU information
      sh '''
      BBSIM_IDS=$(kubectl get pods | grep bbsim | grep -v server | awk '{print $1}')
      IDS=($BBSIM_IDS)

      for bbsim in "${IDS[@]}"
      do
        kubectl exec -t $bbsim -- bbsimctl onu list > $LOG_FOLDER/$bbsim-device-list.txt || true
        kubectl exec -t $bbsim -- bbsimctl service list > $LOG_FOLDER/$bbsim-service-list.txt || true
        kubectl exec -t $bbsim -- bbsimctl olt resources GEM_PORT > $LOG_FOLDER/$bbsim-flows-gem-ports.txt || true
        kubectl exec -t $bbsim -- bbsimctl olt resources ALLOC_ID > $LOG_FOLDER/$bbsim-flows-alloc-ids.txt || true
        kubectl exec -t $bbsim -- bbsimctl olt pons > $LOG_FOLDER/$bbsim-pon-resources.txt || true
      done
      '''
      script {
        // first make sure the port-forward is still running,
        // sometimes Jenkins kills it relardless of the JENKINS_NODE_COOKIE=dontKillMe
        def running = sh (
            script: 'ps aux | grep port-forw | grep -E "onos|voltha" | grep -v grep | wc -l',
            returnStdout: true
        ).trim()
        // if any of the voltha-api, onos-rest, onos-ssh port-forwards are not there
        // kill all and restart
        if (running != "3") {
          start_port_forward(olts)
        }
      }
      // get ONOS debug infos
      sh '''

        sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 apps -a -s > $LOG_FOLDER/onos-apps.txt
        sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 nodes > $LOG_FOLDER/onos-nodes.txt
        sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 masters > $LOG_FOLDER/onos-masters.txt
        sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 roles > $LOG_FOLDER/onos-roles.txt
        sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg get > $LOG_FOLDER/onos-cfg.txt
        sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 netcfg > $LOG_FOLDER/onos-netcfg.txt

        sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 ports > $LOG_FOLDER/onos-ports-list.txt
        sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 hosts > $LOG_FOLDER/onos-hosts-list.txt

        if [ ${withFlows} = true ] ; then
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 volt-olts > $LOG_FOLDER/onos-olt-list.txt
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 flows -s > $LOG_FOLDER/onos-flows-list.txt
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 meters > $LOG_FOLDER/onos-meters-list.txt
        fi

        if [ ${provisionSubscribers} = true ]; then
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 volt-programmed-subscribers > $LOG_FOLDER/onos-programmed-subscribers.txt
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 volt-programmed-meters > $LOG_FOLDER/onos-programmed-meters.txt
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 volt-bpmeter-mappings > $LOG_FOLDER/onos-bpmeter-mappings.txt
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 volt-failed-subscribers > $LOG_FOLDER/onos-failed-subscribers.txt
        fi

        if [ ${withEapol} = true ] ; then
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 aaa-users > $LOG_FOLDER/onos-aaa-users.txt
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 aaa-statistics > $LOG_FOLDER/onos-aaa-statistics.txt
        fi

        if [ ${withDhcp} = true ] ; then
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 dhcpl2relay-allocations > $LOG_FOLDER/onos-dhcp-allocations.txt
        fi

        if [ ${withIgmp} = true ] ; then
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 mcast-host-routes > $LOG_FOLDER/onos-mcast-host-routes.txt
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 mcast-host-show > $LOG_FOLDER/onos-mcast-host-show.txt
          sshpass -e ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 groups > $LOG_FOLDER/onos-groups.txt
        fi
      '''
      // collect etcd metrics
      sh '''
        mkdir -p $WORKSPACE/etcd-metrics
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_debugging_mvcc_keys_total' | jq '.data' > $WORKSPACE/etcd-metrics/etcd-key-count.json || true
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=grpc_server_handled_total{grpc_service="etcdserverpb.KV"}' | jq '.data' > $WORKSPACE/etcd-metrics/etcd-rpc-count.json || true
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_debugging_mvcc_db_total_size_in_bytes' | jq '.data' > $WORKSPACE/etcd-metrics/etcd-db-size.json || true
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_disk_backend_commit_duration_seconds_sum' | jq '.data'  > $WORKSPACE/etcd-metrics/etcd-backend-write-time.json || true
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_disk_backend_commit_duration_seconds_sum' | jq '.data'  > $WORKSPACE/etcd-metrics/etcd-backend-write-time-sum.json || true
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_disk_backend_commit_duration_seconds_bucket' | jq '.data'  > $WORKSPACE/etcd-metrics/etcd-backend-write-time-bucket.json || true
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_disk_wal_fsync_duration_seconds_bucket' | jq '.data'  > $WORKSPACE/etcd-metrics/etcd-wal-fsync-time-bucket.json || true
        curl -s -X GET -G http://10.90.0.101:31301/api/v1/query --data-urlencode 'query=etcd_network_peer_round_trip_time_seconds_bucket' | jq '.data'  > $WORKSPACE/etcd-metrics/etcd-network-peer-round-trip-time-seconds.json || true

      '''
      // get VOLTHA debug infos
      script {
        try {
          sh '''
          voltctl -m 8MB device list -o json > $LOG_FOLDER/device-list.json || true
          python -m json.tool $LOG_FOLDER/device-list.json > $LOG_FOLDER/voltha-devices-list.json || true
          rm $LOG_FOLDER/device-list.json || true
          voltctl -m 8MB device list > $LOG_FOLDER/voltha-devices-list.txt || true

          printf '%s\n' $(voltctl -m 8MB device list | grep olt | awk '{print $1}') | xargs --no-run-if-empty -I# bash -c "voltctl -m 8MB device flows # > $LOG_FOLDER/voltha-device-flows-#.txt" || true
              printf '%s\n' $(voltctl -m 8MB device list | grep olt | awk '{print $1}') | xargs --no-run-if-empty -I# bash -c "voltctl -m 8MB device port list --format 'table{{.PortNo}}\t{{.Label}}\t{{.Type}}\t{{.AdminState}}\t{{.OperStatus}}' # > $LOG_FOLDER/voltha-device-ports-#.txt" || true

          printf '%s\n' $(voltctl -m 8MB logicaldevice list -q) | xargs --no-run-if-empty -I# bash -c "voltctl -m 8MB logicaldevice flows # > $LOG_FOLDER/voltha-logicaldevice-flows-#.txt" || true
          printf '%s\n' $(voltctl -m 8MB logicaldevice list -q) | xargs --no-run-if-empty -I# bash -c "voltctl -m 8MB logicaldevice port list # > $LOG_FOLDER/voltha-logicaldevice-ports-#.txt" || true
          '''
        } catch(e) {
          sh '''
          echo "Can't get device list from voltclt"
          '''
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
      archiveArtifacts artifacts: 'execution-time.txt,logs/*,logs/pprof/*,RobotLogs/**/*,plots/*,etcd-metrics/*'
    }
  }
}

def start_port_forward(olts) {
  sh """
  bbsimRestPortFwd=50071
  for i in {0..${olts.toInteger() - 1}}; do
    daemonize -E JENKINS_NODE_COOKIE="dontKillMe" /usr/local/bin/kubectl port-forward --address 0.0.0.0 -n default svc/bbsim\${i} \${bbsimRestPortFwd}:50071
    ((bbsimRestPortFwd++))
  done
  """
}
