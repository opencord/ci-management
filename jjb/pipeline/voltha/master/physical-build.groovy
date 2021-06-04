// Copyright 2017-present Open Networking Foundation
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

// used to deploy VOLTHA and configure ONOS physical PODs

// NOTE we are importing the library even if it's global so that it's
// easier to change the keywords during a replay
library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://gerrit.opencord.org/ci-management.git'
])

def infraNamespace = "infra"
def volthaNamespace = "voltha"

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: 35, unit: 'MINUTES')
  }
  environment {
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin"
    KUBECONFIG="$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf"
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
    stage ("Parse deployment configuration file") {
      steps {
        sh returnStdout: true, script: "rm -rf ${configBaseDir}"
        sh returnStdout: true, script: "git clone -b master ${cordRepoUrl}/${configBaseDir}"
        script {
          if ( params.workFlow == "DT" ) {
            deployment_config = readYaml file: "${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
          }
          else if ( params.workFlow == "TT" )
          {
            deployment_config = readYaml file: "${configBaseDir}/${configDeploymentDir}/${configFileName}-TT.yaml"
          }
          else
          {
            deployment_config = readYaml file: "${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
          }
        }
      }
    }
    stage('Clean up') {
      steps {
        timeout(15) {
          script {
            helmTeardown(["default", infraNamespace, volthaNamespace])
          }
          timeout(1) {
            sh returnStdout: false, script: '''
            # remove orphaned port-forward from different namespaces
            ps aux | grep port-forw | grep -v grep | awk '{print $2}' | xargs --no-run-if-empty kill -9 || true
            '''
          }
        }
      }
    }
    stage('Install Voltha')  {
      steps {
        timeout(20) {
          script {
            // if we're downloading a voltha-helm-charts patch, then install from a local copy of the charts
            def localCharts = false
            if (volthaHelmChartsChange != "") {
              localCharts = true
            }

            // should the config file be suffixed with the workflow? see "deployment_config"
            def localHelmFlags = "-f $WORKSPACE/${configBaseDir}/${configKubernetesDir}/voltha/${configFileName}.yml --set global.log_level=${logLevel} "

            if (workFlow.toLowerCase() == "dt") {
              localHelmFlags += " --set radius.enabled=false "
            }
            if (workFlow.toLowerCase() == "tt") {
              localHelmFlags += " --set radius.enabled=false --set global.incremental_evto_update=true "
            }

            // NOTE temporary workaround expose ONOS node ports (pod-config needs to be updated to contain these values)
            // and to connect the ofagent to all instances of ONOS
            localHelmFlags = localHelmFlags + " --set onos-classic.onosSshPort=30115 " +
            "--set onos-classic.onosApiPort=30120 " +
            "--set onos-classic.onosOfPort=31653 " +
            "--set onos-classic.individualOpenFlowNodePorts=true " +
            "--set voltha.onos_classic.replicas=${params.NumOfOnos}"

            if (bbsimReplicas.toInteger() != 0) {
              localHelmFlags = localHelmFlags + " --set onu=${onuNumber},pon=${ponNumber} "
            }

            // adding user specified helm flags at the end so they'll have priority over everything else
            localHelmFlags = localHelmFlags + " ${extraHelmFlags}"

            volthaDeploy([
              workflow: workFlow.toLowerCase(),
              extraHelmFlags: localHelmFlags,
              localCharts: localCharts,
              kubeconfig: "$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf",
              onosReplica: params.NumOfOnos,
              atomixReplica: params.NumOfAtomix,
              kafkaReplica: params.NumOfKafka,
              etcdReplica: params.NumOfEtcd,
              bbsimReplica: bbsimReplicas.toInteger(),
              ])
          }
          sh """
          JENKINS_NODE_COOKIE="dontKillMe" _TAG="voltha-api" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${volthaNamespace} svc/voltha-voltha-api 55555:55555; done"&
          JENKINS_NODE_COOKIE="dontKillMe" _TAG="etcd" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-etcd ${params.VolthaEtcdPort}:2379; done"&
          JENKINS_NODE_COOKIE="dontKillMe" _TAG="kafka" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-kafka 9092:9092; done"&
          ps aux | grep port-forward
          """
          getPodsInfo("$WORKSPACE")
        }
      }
    }
    stage('Push Tech-Profile') {
      steps {
        script {
          if ( params.configurePod && params.profile != "Default" ) {
            for(int i=0; i < deployment_config.olts.size(); i++) {
              def tech_prof_directory = "XGS-PON"
              // If no debian package is specified we default to GPON for the ADTRAN OLT.
              if (!deployment_config.olts[i].containsKey("oltDebVersion") || deployment_config.olts[i].oltDebVersion.contains("asgvolt64")){
                tech_prof_directory = "GPON"
              }
              timeout(1) {
                sh returnStatus: true, script: """
                export KUBECONFIG=$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf
                etcd_container=\$(kubectl get pods -n ${infraNamespace} | grep etcd | awk 'NR==1{print \$1}')
                if [[ "${workFlow}" == "TT" ]]; then
                   kubectl cp -n ${infraNamespace} $WORKSPACE/voltha-system-tests/tests/data/TechProfile-TT-HSIA.json \$etcd_container:/tmp/hsia.json
                   kubectl exec -n ${infraNamespace} -it \$etcd_container -- /bin/sh -c 'cat /tmp/hsia.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/${tech_prof_directory}/64'
                   kubectl cp -n ${infraNamespace} $WORKSPACE/voltha-system-tests/tests/data/TechProfile-TT-VoIP.json \$etcd_container:/tmp/voip.json
                   kubectl exec -n ${infraNamespace} -it \$etcd_container -- /bin/sh -c 'cat /tmp/voip.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/${tech_prof_directory}/65'
                   kubectl cp -n ${infraNamespace} $WORKSPACE/voltha-system-tests/tests/data/TechProfile-TT-MCAST-AdditionalBW-None.json \$etcd_container:/tmp/mcast_additionalBW_none.json
                   kubectl exec -n ${infraNamespace} -it \$etcd_container -- /bin/sh -c 'cat /tmp/mcast_additionalBW_none.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/${tech_prof_directory}/66'
                   kubectl cp -n ${infraNamespace} $WORKSPACE/voltha-system-tests/tests/data/TechProfile-TT-MCAST-AdditionalBW-NA.json \$etcd_container:/tmp/mcast_additionalBW_na.json
                   kubectl exec -n ${infraNamespace} -it \$etcd_container -- /bin/sh -c 'cat /tmp/mcast_additionalBW_na.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/${tech_prof_directory}/67'
                else
                   kubectl cp -n ${infraNamespace} $WORKSPACE/voltha-system-tests/tests/data/TechProfile-${profile}.json \$etcd_container:/tmp/flexpod.json
                   kubectl exec -n ${infraNamespace} -it \$etcd_container -- /bin/sh -c 'cat /tmp/flexpod.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/${tech_prof_directory}/64'
                fi
                """
              }
              timeout(1) {
                sh returnStatus: true, script: """
                export KUBECONFIG=$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf
                etcd_container=\$(kubectl get pods -n ${infraNamespace} | grep etcd | awk 'NR==1{print \$1}')
                kubectl exec -n ${infraNamespace} -it \$etcd_container -- /bin/sh -c 'ETCDCTL_API=3 etcdctl get --prefix service/voltha/technology_profiles/${tech_prof_directory}/64'
                """
              }
            }
          }
        }
      }
    }
    stage('Push MIB templates') {
      steps {
        sh """
        export KUBECONFIG=$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf
        etcd_container=\$(kubectl get pods -n ${infraNamespace} | grep etcd | awk 'NR==1{print \$1}')
        kubectl cp -n ${infraNamespace} $WORKSPACE/voltha-system-tests/tests/data/MIB_Alpha.json \$etcd_container:/tmp/MIB_Alpha.json
        kubectl exec -n ${infraNamespace} -it \$etcd_container -- /bin/sh -c 'cat /tmp/MIB_Alpha.json | ETCDCTL_API=3 etcdctl put service/voltha/omci_mibs/go_templates/BRCM/BVM4K00BRA0915-0083/5023_020O02414'
        kubectl exec -n ${infraNamespace} -it \$etcd_container -- /bin/sh -c 'cat /tmp/MIB_Alpha.json | ETCDCTL_API=3 etcdctl put service/voltha/omci_mibs/templates/BRCM/BVM4K00BRA0915-0083/5023_020O02414'
        kubectl cp -n ${infraNamespace} $WORKSPACE/voltha-system-tests/tests/data/MIB_Scom.json \$etcd_container:/tmp/MIB_Scom.json
        kubectl exec -n ${infraNamespace} -it \$etcd_container -- /bin/sh -c 'cat /tmp/MIB_Scom.json | ETCDCTL_API=3 etcdctl put service/voltha/omci_mibs/go_templates/SCOM/Glasfaser-Modem/090140.1.0.304'
        kubectl exec -n ${infraNamespace} -it \$etcd_container -- /bin/sh -c 'cat /tmp/MIB_Scom.json | ETCDCTL_API=3 etcdctl put service/voltha/omci_mibs/templates/SCOM/Glasfaser-Modem/090140.1.0.304'
        """
      }
    }
    stage('Push Sadis-config') {
      steps {
        timeout(1) {
          sh returnStatus: true, script: """
          if [[ "${workFlow}" == "DT" ]]; then
            curl -sSL --user karaf:karaf -X POST -H Content-Type:application/json http://${deployment_config.nodes[0].ip}:30120/onos/v1/network/configuration --data @$WORKSPACE/voltha-system-tests/tests/data/${configFileName}-sadis-DT.json
          elif [[ "${workFlow}" == "TT" ]]; then
            curl -sSL --user karaf:karaf -X POST -H Content-Type:application/json http://${deployment_config.nodes[0].ip}:30120/onos/v1/network/configuration --data @$WORKSPACE/voltha-system-tests/tests/data/${configFileName}-sadis-TT.json
          else
            # this is the ATT case, rename the file in *-sadis-ATT.json so that we can avoid special cases and just load the file
            curl -sSL --user karaf:karaf -X POST -H Content-Type:application/json http://${deployment_config.nodes[0].ip}:30120/onos/v1/network/configuration --data @$WORKSPACE/voltha-system-tests/tests/data/${configFileName}-sadis.json
          fi
          """
        }
      }
    }
    stage('Switch Configurations in ONOS') {
      steps {
        script {
          if ( deployment_config.fabric_switches.size() > 0 ) {
            timeout(1) {
              def netcfg = "$WORKSPACE/${configBaseDir}/${configToscaDir}/voltha/${configFileName}-onos-netcfg-switch.json"
              if (params.inBandManagement){
                netcfg = "$WORKSPACE/${configBaseDir}/${configToscaDir}/voltha/${configFileName}-onos-netcfg-switch-inband.json"
              }
              sh """
              curl -sSL --user karaf:karaf -X POST -H Content-Type:application/json http://${deployment_config.nodes[0].ip}:30120/onos/v1/network/configuration --data @${netcfg}
              curl -sSL --user karaf:karaf -X POST http://${deployment_config.nodes[0].ip}:30120/onos/v1/applications/org.onosproject.segmentrouting/active
              """
            }
            timeout(1) {
              waitUntil {
                sr_active_out = sh returnStatus: true, script: """
                sshpass -p karaf ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "log:set TRACE org.opencord.dhcpl2relay"
                sshpass -p karaf ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "log:set TRACE org.opencord.aaa"
                sshpass -p karaf ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "log:set TRACE org.opencord.olt"
                sshpass -p karaf ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "log:set DEBUG org.onosproject.net.flowobjective.impl.FlowObjectiveManager"
                sshpass -p karaf ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "log:set DEBUG org.onosproject.net.flowobjective.impl.InOrderFlowObjectiveManager"
                curl -sSL --user karaf:karaf -X GET http://${deployment_config.nodes[0].ip}:30120/onos/v1/applications/org.onosproject.segmentrouting | jq '.state' | grep ACTIVE
                sshpass -p karaf ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "cfg set org.onosproject.provider.lldp.impl.LldpLinkProvider enabled false"
                sshpass -p karaf ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "cfg set org.onosproject.net.flow.impl.FlowRuleManager purgeOnDisconnection false"
                sshpass -p karaf ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "cfg set org.onosproject.net.meter.impl.MeterManager purgeOnDisconnection false"
                """
                return sr_active_out == 0
              }
            }
            timeout(5) {
              for(int i=0; i < deployment_config.hosts.src.size(); i++) {
                for(int j=0; j < deployment_config.olts.size(); j++) {
                  def aggPort = -1
                  if(deployment_config.olts[j].serial == deployment_config.hosts.src[i].olt){
                      aggPort = deployment_config.olts[j].aggPort
                      if(aggPort == -1){
                        throw new Exception("Upstream port for the olt is not configured, field aggPort is empty")
                      }
                      sh """
                      sleep 30 # NOTE why are we sleeping?
                      curl -X POST --user karaf:karaf --header 'Content-Type: application/json' --header 'Accept: application/json' -d '{"deviceId": "${deployment_config.fabric_switches[0].device_id}", "vlanId": "${deployment_config.hosts.src[i].s_tag}", "endpoints": [${deployment_config.fabric_switches[0].bngPort},${aggPort}]}' 'http://${deployment_config.nodes[0].ip}:30120/onos/segmentrouting/xconnect'
                      """
                  }
                }
              }
            }
          }
        }
      }
    }
    stage('Reinstall OLT software') {
      steps {
        script {
          if ( params.reinstallOlt ) {
            for(int i=0; i < deployment_config.olts.size(); i++) {
              // NOTE what is oltDebVersion23? is that for VOLTHA-2.3? do we still need this differentiation?
              sh returnStdout: true, script: """
              if [[ "${branch}" != "master" ]] && [[ "${params.inBandManagement}" == "true" ]]; then
                ssh-keyscan -H ${deployment_config.olts[i].sship} >> ~/.ssh/known_hosts
                sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'kill -9 `pgrep -f "[b]ash /opt/openolt/openolt_dev_mgmt_daemon_process_watchdog"` || true'
                sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} "dpkg --install ${deployment_config.olts[i].oltDebVersion23}"
              fi
              if [[ "${branch}" != "master" ]] && [[ "${params.inBandManagement}" == "false" ]]; then
                ssh-keyscan -H ${deployment_config.olts[i].sship} >> ~/.ssh/known_hosts
                sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} "dpkg --install ${deployment_config.olts[i].oltDebVersion23}"
              fi
              if [[ "${branch}" == "master" ]] && [[ "${params.inBandManagement}" == "true" ]]; then
                ssh-keyscan -H ${deployment_config.olts[i].sship} >> ~/.ssh/known_hosts
                sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'kill -9 `pgrep -f "[b]ash /opt/openolt/openolt_dev_mgmt_daemon_process_watchdog"` || true'
                sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} "dpkg --install ${deployment_config.olts[i].oltDebVersion}"
              fi
              if [[ "${branch}" == "master" ]] && [[ "${params.inBandManagement}" == "false" ]]; then
                ssh-keyscan -H ${deployment_config.olts[i].sship} >> ~/.ssh/known_hosts
                sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} "dpkg --install ${deployment_config.olts[i].oltDebVersion}"
              fi
              sleep 10
              """
              timeout(5) {
                waitUntil {
                  olt_sw_present = sh returnStdout: true, script: """
                  if [ "${deployment_config.olts[i].oltDebVersion}" == *"asfvolt16"* ]; then
                    sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'dpkg --list | grep asfvolt16 | wc -l'
                  else
                    sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'dpkg --list | grep asgvolt64 | wc -l'
                  fi
                  if (${deployment_config.olts[i].fortygig}); then
                    if [[ "${params.inBandManagement}" == "true" ]]; then
                      ssh-keyscan -H ${deployment_config.olts[i].sship} >> ~/.ssh/known_hosts
                      sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'mkdir -p /opt/openolt/'
                      sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'cp /root/watchdog-script/* /opt/openolt/'
                      sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'cp /root/bal_cli_appl/example_user_appl /broadcom'
                      sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'cp in-band-startup-script/* /etc/init.d/'
                    fi
                  fi
                  """
                  return olt_sw_present.toInteger() > 0
                }
              }
            }
          }
        }
      }
    }
    stage('Restart OLT processes') {
      steps {
        script {
          for(int i=0; i < deployment_config.olts.size(); i++) {
            int waitTimerForOltUp = 360
            if ( params.inBandManagement ) {
              waitTimerForOltUp = 540
            }
            timeout(15) {
              sh returnStdout: true, script: """
              ssh-keyscan -H ${deployment_config.olts[i].sship} >> ~/.ssh/known_hosts
              sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'rm -f /var/log/openolt.log; rm -f /var/log/dev_mgmt_daemon.log; rm -f /var/log/openolt_process_watchdog.log; reboot -f > /dev/null &' || true
              sleep ${waitTimerForOltUp}
              """
            }
            timeout(15) {
              waitUntil {
                devprocess = sh returnStdout: true, script: "sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'ps -ef | grep dev_mgmt_daemon | wc -l'"
                return devprocess.toInteger() > 0
              }
            }
            timeout(15) {
              waitUntil {
                openoltprocess = sh returnStdout: true, script: "sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'ps -ef | grep openolt | wc -l'"
                return openoltprocess.toInteger() > 0
              }
            }
          }
        }
      }
    }
  }

  post {
    aborted {
      getPodsInfo("$WORKSPACE/failed")
      sh """
      kubectl logs -n voltha -l app.kubernetes.io/part-of=voltha > $WORKSPACE/failed/voltha.log || true
      """
      archiveArtifacts artifacts: '**/*.log,**/*.txt'
    }
    failure {
      getPodsInfo("$WORKSPACE/failed")
      sh """
      kubectl logs -n voltha -l app.kubernetes.io/part-of=voltha > $WORKSPACE/failed/voltha.logs || true
      """
      archiveArtifacts artifacts: '**/*.log,**/*.txt'
    }
    always {
      archiveArtifacts artifacts: '*.txt'
    }
  }
}
