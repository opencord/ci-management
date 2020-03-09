/* voltha-scale-measurements pipeline */
pipeline {
  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="$WORKSPACE/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    TYPE="minimal"
    FANCY=0
    SECONDS=0
    WITH_SIM_ADAPTERS="n"
    WITH_RADIUS="y"
    WITH_BBSIM="y"
    VOLTHA_LOG_LEVEL="WARN"
    CONFIG_SADIS="n"
    ROBOT_MISC_ARGS="-d $WORKSPACE/RobotLogs -v teardown_device:False"
    SSHPASS="karaf"
    DEPLOY_K8S="n"
    EXTRA_HELM_FLAGS="--set onu=${onuPerPon},pon=${ponPorts},delay=${BBSIMdelay},auth=${bbsimAuth},dhcp=${bbsimDhcp}"
  }
  stages {
    stage('set-description') {
      steps {
        script {
          currentBuild.description = "$BUILD_TIMESTAMP"
        }
      }
    }
    stage('cleanup') {
      steps {
        sh '''
          rm -rf onus.txt ports.txt voltha-devices.txt onos-ports.txt total-time.txt onu-activation.txt device-list.json
          for hchart in \$(helm list -q | grep -E -v 'docker-registry|cord-kafka|etcd-operator');
          do
              echo "Purging chart: \${hchart}"
              helm delete --purge "\${hchart}"
          done
          bash /home/cord/voltha-scale/wait_for_pods.sh
          bash /home/cord/voltha-scale/stop_port_forward.sh
        '''
      }
    }
    stage('start') {
      steps {
        sh '''
          #!/usr/bin/env bash
          set -euo pipefail
          '''
      }
    }
    stage('deploy-voltha') {
      options {
        timeout(time:10)
      }
      steps {
        sh '''
          helm install -n onos onf/onos --set images.onos.repository=voltha/voltha-onos --set images.onos.tag=4.0.1

          IFS=: read -r volthaRepo volthaTag <<< ${volthaImg}
          helm install -n voltha onf/voltha -f /home/cord/voltha-scale/voltha-values.yaml --set images.voltha.repository=${volthaRepo},images.voltha.tag=${volthaTag}

          IFS=: read -r openoltAdapterRepo openoltAdapterTag <<< ${openoltAdapterImg}
          helm install -n openolt onf/voltha-adapter-openolt -f /home/cord/voltha-scale/voltha-values.yaml --set images.adapter_open_olt.repository=${openoltAdapterRepo},images.adapter_open_olt.tag=${openoltAdapterTag}

          IFS=: read -r openonuAdapterRepo openonuAdapterTag <<< ${openonuAdapterImg}
          helm install -n openonu onf/voltha-adapter-openonu -f /home/cord/voltha-scale/voltha-values.yaml --set images.adapter_open_olt.repository=${openonuAdapterRepo},images.adapter_open_olt.tag=${openonuAdapterTag}

          IFS=: read -r bbsimRepo bbsimTag <<< ${bbsimImg}
          helm install -n bbsim onf/bbsim --set pon=${ponPorts},onu=${onuPerPon},auth=${bbsimAuth},dhcp=${bbsimDhcp},delay=${BBSIMdelay},images.bbsim.repository=${bbsimRepo},images.bbsim.tag=${bbsimTag}
          helm install -n radius onf/freeradius

          if [ ! -z ${bbsimImg} ];
          then
            IFS=: read -r bbsimRepo bbsimTag <<< ${bbsimImg}
            EXTRA_HELM_FLAGS+=",images.bbsim.repository=${bbsimRepo},images.bbsim.tag=${bbsimTag}"
          fi
          if [ ! -z ${volthaImg} ];
          then
            IFS=: read -r volthaRepo volthaTag <<< ${volthaImg}
            EXTRA_HELM_FLAGS+=",images.voltha.repository=${volthaRepo},images.voltha.tag=${volthaTag}"
          fi
          bash /home/cord/voltha-scale/wait_for_pods.sh
          bash /home/cord/voltha-scale/start_port_forward.sh
          '''
      }
    }
    stage('wait for adapters to be registered') {
      options {
        timeout(time:5)
      }
      steps{
        waitUntil {
          script {
            openolt_res = sh returnStdout: true, script: """
            voltctl adapter list | grep openolt | wc -l
            """

            openonu_res = sh returnStdout: true, script: """
            voltctl adapter list | grep brcm_openomci_onu | wc -l
            """

            return openolt_res.toInteger() == 1 && openonu_res.toInteger() == 1
          }
        }
      }
    }
    stage('MIB-template') {
      steps {
        sh '''
          if [ ${withMibTemplate} = true ] ; then
            rm -f BBSM-12345123451234512345-00000000000001-v1.json
            wget https://raw.githubusercontent.com/opencord/voltha-openonu-adapter/master/templates/BBSM-12345123451234512345-00000000000001-v1.json
            cat BBSM-12345123451234512345-00000000000001-v1.json | kubectl exec -it $(kubectl get pods | grep etcd-cluster | awk 'NR==1{print $1}') etcdctl put service/voltha/omci_mibs/templates/BBSM/12345123451234512345/00000000000001
          fi
        '''
      }
    }
    stage('disable-ONOS-apps') {
      steps {
        sh '''
          #Check withOnosApps and disable apps accordingly
          if [ ${withOnosApps} = false ] ; then
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost app deactivate org.opencord.olt
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost app deactivate org.opencord.aaa
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost app deactivate org.opencord.dhcpl2relay
          fi
        '''
      }
    }
    stage('configuration') {
      steps {
        sh '''
          #Setting LOG level to WARN
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost log:set WARN
          kubectl exec $(kubectl get pods | grep bbsim | awk 'NR==1{print $1}') bbsimctl log warn false
          #Setting link discovery
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost cfg set org.onosproject.provider.lldp.impl.LldpLinkProvider enabled ${setLinkDiscovery}
          #Setting the flow stats collection interval
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost cfg set org.onosproject.provider.of.flow.impl.OpenFlowRuleProvider flowPollFrequency ${flowStatInterval}
          #Setting the ports stats collection interval
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost cfg set org.onosproject.provider.of.device.impl.OpenFlowDeviceProvider portStatsPollFrequency ${portsStatInterval}
          # extending voltctl timeout
          sed -i 's/timeout: 10s/timeout: 5m/g' /home/cord/.volt/config
        '''
      }
    }
    stage('execute') {
      options {
        timeout(time:10)
      }
      stages {
        stage('ONUs-enabled') {
          steps {
            sh '''
              if [ -z ${expectedOnus} ]
              then
                echo -e "You need to set the target ONU number\n"
                exit 1
              fi

              voltctl device create -t openolt -H bbsim:50060
              voltctl device enable $(voltctl device list --filter Type~openolt -q)
              # check ONUs reached Active State in VOLTHA
              i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
              until [ $i -eq ${expectedOnus} ]
              do
                echo "$i ONUs ACTIVE of ${expectedOnus} expected (time: $SECONDS)"
                sleep ${pollInterval}
                i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
              done
              echo "${expectedOnus} ONUs Activated in $SECONDS seconds (time: $SECONDS)"
              echo $SECONDS > voltha-devices.txt
            '''
          }
        }
        stage('ONOS-ports') {
          steps {
            sh '''
              # Check ports showed up in ONOS
              z=$(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost ports -e | grep BBSM | wc -l)
              until [ $z -eq ${expectedOnus} ]
              do
                echo "${z} enabled ports of ${expectedOnus} expected (time: $SECONDS)"
                sleep ${pollInterval}
                z=$(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost ports -e | grep BBSM | wc -l)
              done
              echo "${expectedOnus} ports enabled in $SECONDS seconds (time: $SECONDS)"
              echo $SECONDS > onos-ports.txt
              echo "ONOS-Duration(s)" > total-time.txt
              echo "VOLTHA-Duration(s)" > onu-activation.txt
              cat voltha-devices.txt >> onu-activation.txt
              paste voltha-devices.txt onos-ports.txt | awk '{print ($1 + $2)}' >> total-time.txt
            '''
          }
        }
      }
    }
  }
  post {
    success {
      plot([
        csvFileName: 'plot-onu-activation.csv',
        csvSeries: [[displayTableFlag: false, exclusionValues: '', file: 'onu-activation.txt', inclusionFlag: 'OFF', url: ''], [displayTableFlag: false, exclusionValues: '', file: 'total-time.txt', inclusionFlag: 'OFF', url: '']],
        group: 'Voltha-Scale-Numbers', numBuilds: '100', style: 'line', title: "Time (${BBSIMdelay}s Delay)", yaxis: 'Time (s)', useDescr: true
      ])
    }
    always {
      sh '''
        echo $(voltctl device list | grep -v OLT | grep ACTIVE | wc -l) > onus.txt
        echo "#-of-ONUs" > no_onus.txt
        cat onus.txt >> no_onus.txt

        echo $(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost ports -e | grep BBSM | wc -l) > ports.txt
        echo "#-of-ports" > no_ports.txt
        cat ports.txt >> no_ports.txt

        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
        voltctl device list -o json > device-list.json
        python -m json.tool device-list.json > volt-device-list.json
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost ports > onos-ports.txt
        '''
      plot([
        csvFileName: 'plot-numbers.csv',
        csvSeries: [[displayTableFlag: false, exclusionValues: '', file: 'no_onus.txt', inclusionFlag: 'OFF', url: ''], [displayTableFlag: false, exclusionValues: '', file: 'no_ports.txt', inclusionFlag: 'OFF', url: '']],
        group: 'Voltha-Scale-Numbers', numBuilds: '100', style: 'line', title: "Activated ONUs and Recognized Ports", yaxis: 'Number of Ports/ONUs', useDescr: true
      ])

      archiveArtifacts artifacts: '*.log,*.json,*txt'

    }
  }
}