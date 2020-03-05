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
          currentBuild.description = "${onuPerPon} ONU x ${ponPorts} PON - BBSIM Delay ${BBSIMdelay}"
        }
      }
    }
    stage('cleanup') {
      steps {
        sh '''
          rm -rf voltha-devices.txt onos-ports.txt total-time.txt onu-activation.txt
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
      steps {
        sh '''
          helm install -n onos onf/onos --set images.onos.repository=voltha/voltha-onos --set images.onos.tag=4.0.1
          helm install -n voltha onf/voltha -f /home/cord/voltha-scale/voltha-values.yaml
          helm install -n openolt onf/voltha-adapter-openolt -f /home/cord/voltha-scale/voltha-values.yaml
          helm install -n openonu onf/voltha-adapter-openonu -f /home/cord/voltha-scale/voltha-values.yaml

          helm install -n bbsim onf/bbsim --set pon=${ponPorts},onu=${onuPerPon},auth=${bbsimAuth},dhcp=${bbsimDhcp},delay=${BBSIMdelay}
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
    always {
      plot([
        csvFileName: 'plot-onu-activation.csv',
        csvSeries: [[displayTableFlag: false, exclusionValues: '', file: 'onu-activation.txt', inclusionFlag: 'OFF', url: ''], [displayTableFlag: false, exclusionValues: '', file: 'total-time.txt', inclusionFlag: 'OFF', url: '']],
        group: 'Voltha-Scale-Numbers', numBuilds: '100', style: 'line', title: 'Time (200ms Delay)', useDescr: true, yaxis: 'Time (s)'
      ])
      script {
        sh '''
          kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
          voltctl device list -o json > device-list.json
          python -m json.tool device-list.json > volt-device-list.json
          rm device-list.json
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@localhost ports > onos-ports-list.txt
        '''
      }
      archiveArtifacts artifacts: '*.log,*.json,*txt'
    }
    success {
      sh '''
        #!/usr/bin/env bash
        set +e
      '''
    }
  }
}
