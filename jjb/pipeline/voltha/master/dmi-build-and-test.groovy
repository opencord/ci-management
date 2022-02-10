// Copyright 2022-present Open Networking Foundation
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

def deploy_custom_chart(namespace, name, chart, extraHelmFlags) {
  sh """
    helm install --create-namespace --set defaults.image_pullPolicy=Always --namespace ${namespace} ${extraHelmFlags} ${name} ${chart}
   """
}

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: 45, unit: 'MINUTES')
  }
  environment {
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin"
    KUBECONFIG="$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    LOG_FOLDER="$WORKSPACE/dmi/"
    APPS_TO_LOG="${OltDevMgr}"
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
        sh returnStdout: true, script: "git clone -b ${branch} ${cordRepoUrl}/${configBaseDir}"
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
      when {
        expression {
          return installVoltha.toBoolean()
        }
      }
      steps {
        timeout(20) {
          installVoltctl("${branch}")
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
                if (enableMultiUni.toBoolean()) {
                    localHelmFlags += " --set voltha-adapter-openonu.adapter_open_onu.uni_port_mask=${uniPortMask} "
                }
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

            def numberOfAdaptersToWait = 2

            if(openoltAdapterChart != "onf/voltha-adapter-openolt") {
              localHelmFlags = localHelmFlags + " --set voltha-adapter-openolt.enabled=false"
              // We skip waiting for adapters in the volthaDeploy step because it's already waiting for
              // both of them after the deployment of the custom olt adapter. See line 156.
              numberOfAdaptersToWait = 0
            }

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
              adaptersToWait: numberOfAdaptersToWait,
              ])

            if(openoltAdapterChart != "onf/voltha-adapter-openolt"){
              extraHelmFlags = extraHelmFlags + " --set global.log_level=${logLevel}"
              deploy_custom_chart(volthaNamespace, oltAdapterReleaseName, openoltAdapterChart, extraHelmFlags)
              waitForAdapters([
                adaptersToWait: 2
              ])
            }
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
    stage('Deploy Device Manager Interface Chart') {
      steps {
        script {
          deploy_custom_chart('default', 'olt-device-manager', dmiChart, extraHelmFlags)
        }
        println "Wait for olt-device-manager to start"
        sh """
            set +x
            devmgr=\$(kubectl get pods -l app.kubernetes.io/name=${params.OltDevMgr} --no-headers | grep "0/" | wc -l)
            while [[ \$devmgr != 0 ]]; do
              sleep 5
              devmgr=\$(kubectl get pods -l app.kubernetes.io/name=${params.OltDevMgr} --no-headers | grep "0/" | wc -l)
            done
        """
        sh """
          JENKINS_NODE_COOKIE="dontKillMe" _TAG="${params.OltDevMgr}" bash -c "while true; do kubectl port-forward --address 0.0.0.0 svc/${params.OltDevMgr} 50051; done"&
          ps aux | grep port-forward
        """
      }
    }
    stage('Start logging') {
      steps {
        sh returnStdout: false, script: '''
          # start logging with kail
          cd $WORKSPACE
          mkdir -p $LOG_FOLDER
          list=($APPS_TO_LOG)
          for app in "${list[@]}"
          do
            echo "Starting logs for: ${app}"
            _TAG=kail-$app kail -l app.kubernetes.io/name=$app --since 1h > $LOG_FOLDER/$app.log&
          done
        '''
      }
    }
    stage('Reinstall OLT software') {
      steps {
        script {
          if ( params.reinstallOlt ) {
            for(int i=0; i < deployment_config.olts.size(); i++) {
              sh returnStdout: true, script: """
              ssh-keyscan -H ${deployment_config.olts[i].sship} >> ~/.ssh/known_hosts
              if [ "${params.inBandManagement}" == "true" ]; then
                sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'kill -9 `pgrep -f "[b]ash /opt/openolt/openolt_dev_mgmt_daemon_process_watchdog"` || true'
              fi
              sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} "dpkg --install ${deployment_config.olts[i].oltDebVersion}"
              sleep 10
              """
              timeout(5) {
                waitUntil {
                  olt_sw_present = sh returnStdout: true, script: """
                  if [[ "${deployment_config.olts[i].oltDebVersion}" == *"asfvolt16"* ]]; then
                    sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'dpkg --list | grep asfvolt16 | wc -l'
                  elif [[ "${deployment_config.olts[i].oltDebVersion}" == *"asgvolt64"* ]]; then
                    sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'dpkg --list | grep asgvolt64 | wc -l'
                  elif [[ "${deployment_config.olts[i].oltDebVersion}" == *"rlt-1600x-w"* ]]; then
                    sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'dpkg --list | grep rlt-1600x-w | wc -l'
                  elif [[ "${deployment_config.olts[i].oltDebVersion}" == *"rlt-1600g-w"* ]]; then
                    sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'dpkg --list | grep rlt-1600g-w | wc -l'
                  elif [[ "${deployment_config.olts[i].oltDebVersion}" == *"rlt-3200g-w"* ]]; then
                    sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'dpkg --list | grep rlt-3200g-w | wc -l'
                  else
                    echo Unknown Debian package for openolt
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
          if ( params.restartOlt ) {
            //rebooting OLTs
            for(int i=0; i < deployment_config.olts.size(); i++) {
              timeout(15) {
                sh returnStdout: true, script: """
                ssh-keyscan -H ${deployment_config.olts[i].sship} >> ~/.ssh/known_hosts
                sshpass -p ${deployment_config.olts[i].pass} ssh -l ${deployment_config.olts[i].user} ${deployment_config.olts[i].sship} 'rm -f /var/log/openolt.log; rm -f /var/log/dev_mgmt_daemon.log; rm -f /var/log/openolt_process_watchdog.log; reboot > /dev/null &' || true
                """
              }
            }
            sh returnStdout: true, script: """
            sleep ${params.waitTimerForOltUp}
            """
            //Checking dev_management_deamon and openoltprocesses
            for(int i=0; i < deployment_config.olts.size(); i++) {
              if ( params.oltAdapterReleaseName != "open-olt" ) {
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
    }
    stage('Run Device Management Interface Tests') {
      environment {
        ROBOT_FILE="dmi-hw-management.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs"
        ROBOT_CONFIG_FILE="$WORKSPACE/voltha-system-tests/tests/data/dmi-components-adtran.yaml"
      }
      steps {
        sh """
          mkdir -p $ROBOT_LOGS_DIR
          export ROBOT_MISC_ARGS="--removekeywords wuks -e notreadyDMI -i functionalDMI -d $ROBOT_LOGS_DIR"
          make -C $WORKSPACE/voltha-system-tests voltha-dmi-test || true
        """
      }
    }
  }

  post {
    always {
      getPodsInfo("$WORKSPACE")
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
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: 'RobotLogs/log*.html',
        otherFiles: '',
        outputFileName: 'RobotLogs/output*.xml',
        outputPath: '.',
        passThreshold: 100,
        reportFileName: 'RobotLogs/report*.html',
        unstableThreshold: 0,
        onlyCritical: true]);
      archiveArtifacts artifacts: '**/*.txt,**/*.gz,*.gz,**/*.log'
    }
  }
}
