/* voltha-scale-measurements pipeline */
pipeline {
  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  environment {
    SSHPASS="karaf"
  }
  stages {
    stage('Set build description') {
      steps {
        script {
          currentBuild.description = "$BUILD_TIMESTAMP"
        }
      }
    }
    stage('Cleanup') {
      steps {
        sh '''
          rm -rf *.txt
          for hchart in \$(helm list -q | grep -E -v 'docker-registry|kafkacat|etcd-operator');
          do
              echo "Purging chart: \${hchart}"
              helm delete --purge "\${hchart}"
          done
          bash /home/cord/voltha-scale/wait_for_pods.sh
          bash /home/cord/voltha-scale/stop_port_forward.sh
        '''
      }
    }
    stage('Start') {
      steps {
        sh '''
          #!/usr/bin/env bash
          set -euo pipefail
          '''
      }
    }
    stage('Deploy voltha') {
      options {
        timeout(time:10)
      }
      steps {
        sh '''
          helm repo update
          helm install -n cord-kafka incubator/kafka -f /home/cord/voltha-scale/voltha-values.yaml --version 0.13.3 --set replicas=${numOfKafka} --set persistence.enabled=false --set zookeeper.replicaCount=${numOfKafka} --set zookeeper.persistence.enabled=false
          helm install -n nem-monitoring cord/nem-monitoring --set kpi_exporter.enabled=false,dashboards.xos=false,dashboards.onos=false,dashboards.aaa=false,dashboards.voltha=false

          helm install -n radius onf/freeradius ${extraHelmFlags}

          # Multi BBSim sadis server (to be migrated in ONF and included in the helm-chart)
          # kubectl create -f https://raw.githubusercontent.com/ciena/bbsim-sadis-server/master/bbsim-sadis-server.yaml

          # NOTE wait for the infrastructure to be running before installing VOLTHA
          bash /home/cord/voltha-scale/wait_for_pods.sh

          IFS=: read -r onosRepo onosTag <<< ${onosImg}
          helm install -n onos onf/onos --set images.onos.repository=${onosRepo} --set images.onos.tag=${onosTag} ${extraHelmFlags}

          IFS=: read -r volthaRepo volthaTag <<< ${volthaImg}
          IFS=: read -r ofAgentRepo ofAgentTag <<< ${ofAgentImg}
          helm install -n voltha ${volthaChart} -f /home/cord/voltha-scale/voltha-values.yaml --set defaults.log_level=${logLevel},images.rw_core.repository=${volthaRepo},images.rw_core.tag=${volthaTag},images.ofagent_go.repository=${ofAgentRepo},images.ofagent_go.tag=${ofAgentTag} ${extraHelmFlags}

          IFS=: read -r openoltAdapterRepo openoltAdapterTag <<< ${openoltAdapterImg}
          helm install -n openolt ${openoltAdapterChart} -f /home/cord/voltha-scale/voltha-values.yaml --set defaults.log_level=${logLevel},images.adapter_open_olt.repository=${openoltAdapterRepo},images.adapter_open_olt.tag=${openoltAdapterTag} ${extraHelmFlags}

          IFS=: read -r openonuAdapterRepo openonuAdapterTag <<< ${openonuAdapterImg}
          helm install -n openonu ${openonuAdapterChart} -f /home/cord/voltha-scale/voltha-values.yaml --set defaults.log_level=${logLevel},images.adapter_open_onu.repository=${openonuAdapterRepo},images.adapter_open_onu.tag=${openonuAdapterTag} ${extraHelmFlags}

          IFS=: read -r bbsimRepo bbsimTag <<< ${bbsimImg}

          for i in $(seq 1 $((${numOfBbsim}))); do
            helm install -n bbsim-$i ${bbsimChart} --set olt_id=$i,enablePerf=true,pon=${ponPorts},onu=${onuPerPon},auth=${bbsimAuth},dhcp=${bbsimDhcp},delay=${BBSIMdelay},images.bbsim.repository=${bbsimRepo},images.bbsim.tag=${bbsimTag} ${extraHelmFlags}
          done

          bash /home/cord/voltha-scale/wait_for_pods.sh
          bash /home/cord/voltha-scale/start_port_forward.sh
          '''
      }
    }
    stage('Wait for Adapters to be registered in VOLTHA') {
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

            return openolt_res.toInteger() >= 1 && openonu_res.toInteger() >= 1
          }
        }
      }
    }
    stage('Push MIB template to ETCD') {
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
    stage('Configure ONOS and VOLTHA') {
      steps {
        sh '''
          #Setting LOG level to ${logLevel}
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 log:set ${logLevel}
          kubectl exec $(kubectl get pods | grep bbsim | awk 'NR==1{print $1}') bbsimctl log warn false

          #Setting link discovery
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.provider.lldp.impl.LldpLinkProvider enabled ${setLinkDiscovery}

          # extending voltctl timeout
          sed -i 's/timeout: 10s/timeout: 5m/g' /home/cord/.volt/config

          #Check withOnosApps and disable apps accordingly
          if [ ${withOnosApps} = false ] ; then
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 app deactivate org.opencord.olt
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 app deactivate org.opencord.aaa
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 app deactivate org.opencord.dhcpl2relay

            #Setting the flow and ports stats collection interval
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.provider.of.flow.impl.OpenFlowRuleProvider flowPollFrequency ${flowStatInterval}
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 cfg set org.onosproject.provider.of.device.impl.OpenFlowDeviceProvider portStatsPollFrequency ${portsStatInterval}
          else
            echo "When using the apps flow and port stats collection interval is not supported"
            curl --fail -sSL --user karaf:karaf -X POST -H Content-Type:application/json "http://127.0.0.1:30120/onos/v1/network/configuration/apps/org.opencord.sadis" --data '{
              "sadis": {
                  "integration": {
                      "url": "http://bbsim-sadis-server.default.svc:58080/subscribers/%s",
                      "cache": {
                          "enabled": true,
                          "maxsize": 50,
                          "ttl": "PT1m"
                      }
                  }
              },
              "bandwidthprofile": {
                  "integration": {
                      "url": "http://bbsim-sadis-server.default.svc:58080/profiles/%s",
                      "cache": {
                          "enabled": true,
                          "maxsize": 50,
                          "ttl": "PT1m"
                      }
                  }
              }
            }'
          fi
        '''
      }
    }
    stage('Set timeout at 10 minutes') {
      options {
        timeout(time:10)
      }
      stages {
        stage('Activate OLTs') {
          steps {
            sh '''
            for i in $(seq 1 $((${numOfBbsim}))); do
              voltctl device create -t openolt -H bbsim-$i:50060 -m 0f:f1:ce:c$i:ff:ee
            done
            voltctl device list --filter Type~openolt -q | xargs voltctl device enable
            '''
          }
        }
        stage('Wait for ONUs to be enabled') {
          steps {
            sh '''
              if [ -z ${expectedOnus} ]
              then
                echo -e "You need to set the target ONU number\n"
                exit 1
              fi

              # check ONUs reached Active State in VOLTHA
              i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
              until [ $i -eq ${expectedOnus} ]
              do
                echo "$i ONUs ACTIVE of ${expectedOnus} expected (time: $SECONDS)"
                sleep ${pollInterval}
                i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
              done
              echo "${expectedOnus} ONUs Activated in $SECONDS seconds (time: $SECONDS)"

              echo $SECONDS > voltha-devices-time-num.txt

              echo "VOLTHA Duration(s)" > voltha-devices-time.txt
              cat voltha-devices-time-num.txt >> voltha-devices-time.txt
            '''
          }
        }
        stage('Wait for ports to be discovered in ONOS') {
          steps {
            sh '''
              # Check ports showed up in ONOS
              z=$(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 ports -e | grep BBSM | wc -l)
              until [ $z -eq ${expectedOnus} ]
              do
                echo "${z} enabled ports of ${expectedOnus} expected (time: $SECONDS)"
                sleep ${pollInterval}
                z=$(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 ports -e | grep BBSM | wc -l)
              done
              echo "${expectedOnus} ports enabled in $SECONDS seconds (time: $SECONDS)"

              echo $SECONDS > temp.txt
              paste voltha-devices-time-num.txt temp.txt | awk '{print ($1 + $2)}' > onos-ports-time-num.txt

              echo "PORTs Duration(s)" > onos-ports-time.txt
              cat onos-ports-time-num.txt >> onos-ports-time.txt
            '''
          }
        }
        stage('Wait for flows to be programmed') {
          steps {
            sh '''
            if [ ${withOnosApps} = false ] ; then
              echo "ONOS Apps are not enabled, nothing to check"
            else
              # wait until all flows are in added state
              z=$(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 flows -s | grep PENDING | wc -l)
              until [ $z -eq 0 ]
              do
                echo "${z} flows in PENDING state (time: $SECONDS)"
                sleep ${pollInterval}
                z=$(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 flows -s | grep PENDING | wc -l)
              done
              echo "All flows correctly programmed (time: $SECONDS)"

              echo $SECONDS > temp.txt
              paste onos-ports-time-num.txt temp.txt | awk '{print ($1 + $2)}' > onos-flows-time-num.txt

              echo "FLOWs Duration(s)" > onos-flows-time.txt
              cat onos-flows-time-num.txt >> onos-flows-time.txt
            fi
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
        csvSeries: [
          [displayTableFlag: false, exclusionValues: '', file: 'voltha-devices-time.txt', inclusionFlag: 'OFF', url: ''],
          [displayTableFlag: false, exclusionValues: '', file: 'onos-ports-time.txt', inclusionFlag: 'OFF', url: ''],
          [displayTableFlag: false, exclusionValues: '', file: 'onos-flows-time.txt', inclusionFlag: 'OFF', url: '']
        ],
        group: 'Voltha-Scale-Numbers', numBuilds: '20', style: 'line', title: "Time (${BBSIMdelay}s Delay)", yaxis: 'Time (s)', useDescr: true
      ])
    }
    always {
      // count how many ONUs have been activated
      sh '''
        echo "#-of-ONUs" > voltha-devices-count.txt
        echo $(voltctl device list | grep -v OLT | grep ACTIVE | wc -l) >> voltha-devices-count.txt
      '''
      // count how many ports have been discovered
      sh '''
        echo "#-of-ports" > onos-ports-count.txt
        echo $(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 ports -e | grep BBSM | wc -l) >> onos-ports-count.txt
      '''
      // count how many flows have been provisioned
      sh '''
        echo "#-of-flows" > onos-flows-count.txt
        echo $(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 flows -s | grep ADDED | wc -l) >> onos-flows-count.txt
      '''
      // check which containers were used in this build
      sh '''
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq
      '''
      // dump all the VOLTHA devices informations
      sh '''
        voltctl device list -o json > device-list.json
        python -m json.tool device-list.json > voltha-devices-list.json
      '''
      // get ports and flows from ONOS
      sh '''
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 ports > onos-ports-list.txt
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@127.0.0.1 flows -s > onos-flows-list.txt
      '''
      // collect the CPU usage
      sh '''
        curl -s -X GET -G http://127.0.0.1:31301/api/v1/query --data-urlencode 'query=avg(rate(container_cpu_usage_seconds_total[10m])*100) by (pod_name)' | jq . > cpu-usage.json
      '''
      // get all the logs from kubernetes PODs
      sh '''
        kubectl get pods -o wide
        kubectl logs -l app=adapter-open-olt > open-olt-logs.txt
        kubectl logs -l app=adapter-open-onu > open-onu-logs.txt
        kubectl logs -l app=rw-core > voltha-rw-core-logs.txt
        kubectl logs -l app=ofagent > voltha-ofagent-logs.txt
        kubectl logs -l app=bbsim > bbsim-logs.txt
      '''
      // cleanup of things we don't want to archive
      sh '''
        rm -rf BBSM-12345123451234512345-00000000000001-v1.json device-list.json temp.txt
      '''
      // compile a plot of the activate informations
      plot([
        csvFileName: 'plot-numbers.csv',
        csvSeries: [
          [displayTableFlag: false, exclusionValues: '', file: 'voltha-devices-count.txt', inclusionFlag: 'OFF', url: ''],
          [displayTableFlag: false, exclusionValues: '', file: 'onos-ports-count.txt', inclusionFlag: 'OFF', url: '']
        ],
        group: 'Voltha-Scale-Numbers', numBuilds: '100', style: 'line', title: "Activated ONUs and Recognized Ports", yaxis: 'Number of Ports/ONUs', useDescr: true
      ])

      archiveArtifacts artifacts: '*.log,*.json,*txt'

    }
  }
}
