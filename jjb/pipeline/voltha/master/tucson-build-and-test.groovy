
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
def clusterName = "kind-ci"
pipeline {
  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: 120, unit: 'MINUTES')
  }
  environment {
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin"
    KUBECONFIG="$HOME/.kube/kind-${clusterName}"
    VOLTCONFIG="$HOME/.volt/config"
  }
  stages{
    stage('Download Code') {
      steps {
        getVolthaCode([
          branch: "${branch}",
          gerritProject: "${gerritProject}",
          gerritRefspec: "${gerritRefspec}",
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

          if (params.workflow.toUpperCase() == "TT") {
            error("The Tucson POD does not support TT workflow at the moment")
          }

          if ( params.workflow.toUpperCase() == "DT" ) {
            deployment_config = readYaml file: "${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
          }
          else if ( params.workflow.toUpperCase() == "TT" ) {
            deployment_config = readYaml file: "${configBaseDir}/${configDeploymentDir}/${configFileName}-TT.yaml"
          }
          else {
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
            ps aux | grep port-forw | grep -v grep | awk '{print $2}' | xargs --no-run-if-empty kill -9
            '''
          }
        }
      }
    }
    stage('Build patch') {
      steps {
        // NOTE that the correct patch has already been checked out
        // during the getVolthaCode step
        buildVolthaComponent("${gerritProject}")
      }
    }
    stage('Create K8s Cluster') {
      steps {
        script {
          def clusterExists = sh returnStdout: true, script: """
          kind get clusters | grep ${clusterName} | wc -l
          """
          if (clusterExists.trim() == "0") {
            createKubernetesCluster([nodes: 3, name: clusterName])
          }
        }
      }
    }
    stage('Load image in kind nodes') {
      steps {
        loadToKind()
      }
    }
    stage('Install Voltha')  {
      steps {
        timeout(20) {
          script {
            imageFlags = getVolthaImageFlags(gerritProject)
            // if we're downloading a voltha-helm-charts patch, then install from a local copy of the charts
            def localCharts = false
            if (volthaHelmChartsChange != "" || gerritProject == "voltha-helm-charts") {
              localCharts = true
            }
            def flags = "-f $WORKSPACE/${configBaseDir}/${configKubernetesDir}/voltha/${configFileName}.yml ${imageFlags} "
            // NOTE temporary workaround expose ONOS node ports (pod-config needs to be updated to contain these values)
            flags = flags + "--set onos-classic.onosSshPort=30115 " +
            "--set onos-classic.onosApiPort=30120 " +
            "--set onos-classic.onosOfPort=31653 " +
            "--set onos-classic.individualOpenFlowNodePorts=true " + extraHelmFlags
            volthaDeploy([
              workflow: workFlow.toLowerCase(),
              extraHelmFlags: flags,
              localCharts: localCharts,
              kubeconfig: "$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf",
              onosReplica: 3,
              atomixReplica: 3,
              kafkaReplica: 3,
              etcdReplica: 3,
              ])
          }
          // start logging
          sh """
          mkdir -p $WORKSPACE/${workFlow}
          _TAG=kail-${workFlow} kail -n infra -n voltha > $WORKSPACE/${workFlow}/onos-voltha-combined.log &
          """
          sh """
          JENKINS_NODE_COOKIE="dontKillMe" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${volthaNamespace} svc/voltha-voltha-api 55555:55555; done"&
          JENKINS_NODE_COOKIE="dontKillMe" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-etcd 2379:2379; done"&
          JENKINS_NODE_COOKIE="dontKillMe" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-kafka 9092:9092; done"&
          ps aux | grep port-forward
          """
          getPodsInfo("$WORKSPACE")
        }
      }
    }
    stage('Deploy Kafka Dump Chart') {
      steps {
        script {
          sh returnStdout: false, script: """
              helm repo add cord https://charts.opencord.org
              helm repo update
              if helm version -c --short|grep v2 -q; then
                helm install -n voltha-kafka-dump cord/voltha-kafka-dump
              else
                helm install voltha-kafka-dump cord/voltha-kafka-dump
              fi
          """
        }
      }
    }
    stage('Push Tech-Profile') {
      when {
        expression { params.profile != "Default" }
      }
      steps {
        sh returnStdout: false, script: """
        etcd_container=\$(kubectl get pods -n voltha | grep voltha-etcd-cluster | awk 'NR==1{print \$1}')
        kubectl cp $WORKSPACE/voltha-system-tests/tests/data/TechProfile-${profile}.json voltha/\$etcd_container:/tmp/flexpod.json
        kubectl exec -it \$etcd_container -n voltha -- /bin/sh -c 'cat /tmp/flexpod.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/XGS-PON/64'
        """
      }
    }

    stage('Push Sadis-config') {
      steps {
        sh returnStdout: false, script: """
        ssh-keygen -R [${deployment_config.nodes[0].ip}]:30115
        ssh-keyscan -p 30115 -H ${deployment_config.nodes[0].ip} >> ~/.ssh/known_hosts
        sshpass -p karaf ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "log:set TRACE org.opencord.dhcpl2relay"
        sshpass -p karaf ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "log:set TRACE org.opencord.aaa"
        sshpass -p karaf ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "log:set TRACE org.opencord.olt"
        sshpass -p karaf ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "log:set DEBUG org.onosproject.net.flowobjective.impl.FlowObjectiveManager"
        sshpass -p karaf ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 30115 karaf@${deployment_config.nodes[0].ip} "log:set DEBUG org.onosproject.net.flowobjective.impl.InOrderFlowObjectiveManager"

        if [[ "${workFlow.toUpperCase()}" == "DT" ]]; then
          curl -sSL --user karaf:karaf -X POST -H Content-Type:application/json http://${deployment_config.nodes[0].ip}:30120/onos/v1/network/configuration --data @$WORKSPACE/voltha-system-tests/tests/data/${configFileName}-sadis-DT.json
        elif [[ "${workFlow.toUpperCase()}" == "TT" ]]; then
          curl -sSL --user karaf:karaf -X POST -H Content-Type:application/json http://${deployment_config.nodes[0].ip}:30120/onos/v1/network/configuration --data @$WORKSPACE/voltha-system-tests/tests/data/${configFileName}-sadis-TT.json
        else
          # this is the ATT case, rename the file in *-sadis-ATT.json so that we can avoid special cases and just load the file
          curl -sSL --user karaf:karaf -X POST -H Content-Type:application/json http://${deployment_config.nodes[0].ip}:30120/onos/v1/network/configuration --data @$WORKSPACE/voltha-system-tests/tests/data/${configFileName}-sadis.json
        fi
        """
      }
    }
    stage('Reinstall OLT software') {
      when {
        expression { params.reinstallOlt }
      }
      steps {
        script {
          deployment_config.olts.each { olt ->
            sh returnStdout: false, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'dpkg --remove asfvolt16 && dpkg --purge asfvolt16'"
            waitUntil {
              olt_sw_present = sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'dpkg --list | grep asfvolt16 | wc -l'"
              return olt_sw_present.toInteger() == 0
            }
            if ( params.branch == 'voltha-2.3' ) {
              oltDebVersion = oltDebVersionVoltha23
            } else {
              oltDebVersion = oltDebVersionMaster
            }
            sh returnStdout: false, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'dpkg --install ${oltDebVersion}'"
            waitUntil {
              olt_sw_present = sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'dpkg --list | grep asfvolt16 | wc -l'"
              return olt_sw_present.toInteger() == 1
            }
            if ( olt.fortygig ) {
              // If the OLT is connected to a 40G switch interface, set the NNI port to be downgraded
              sh returnStdout: false, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'echo port ce128 sp=40000 >> /broadcom/qax.soc ; /opt/bcm68620/svk_init.sh'"
            }
          }
        }
      }
    }

    stage('Restart OLT processes') {
      steps {
        script {
          deployment_config.olts.each { olt ->
            sh returnStdout: false, script: """
            ssh-keyscan -H ${olt.ip} >> ~/.ssh/known_hosts
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'rm -f /var/log/openolt.log; rm -f /var/log/dev_mgmt_daemon.log; reboot'
            sleep 120
            """
            waitUntil {
              onu_discovered = sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'grep \"onu discover indication\" /var/log/openolt.log | wc -l'"
              return onu_discovered.toInteger() > 0
            }
          }
        }
      }
    }
    stage('Run E2E Tests') {
      steps {
        script {
          // different workflows need different make targets and different robot files
          if ( params.workflow.toUpperCase() == "DT" ) {
            robotConfigFile = "${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
            robotFile = "Voltha_PODTests.robot"
            makeTarget = "voltha-dt-test"
          }
          else if ( params.workflow.toUpperCase() == "TT" ) {
            robotConfigFile = "${configBaseDir}/${configDeploymentDir}/${configFileName}-TT.yaml"
            robotFile = "Voltha_PODTests.robot"
            makeTarget = "voltha-tt-test"
          }
          else {
            robotConfigFile = "${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
            robotFile = "Voltha_PODTests.robot"
            makeTarget = "voltha-test"
          }
        }
        sh returnStdout: false, script: """
        mkdir -p $WORKSPACE/RobotLogs

        export ROBOT_CONFIG_FILE="$WORKSPACE/${robotConfigFile}"
        export ROBOT_MISC_ARGS="${params.extraRobotArgs} --removekeywords wuks -d $WORKSPACE/RobotLogs -v container_log_dir:$WORKSPACE "
        export ROBOT_FILE="${robotFile}"

        # If the Gerrit comment contains a line with "functional tests" then run the full
        # functional test suite.  This covers tests tagged either 'sanity' or 'functional'.
        # Note: Gerrit comment text will be prefixed by "Patch set n:" and a blank line
        REGEX="functional tests"
        if [[ "${gerritComment}" =~ \$REGEX ]]; then
          ROBOT_MISC_ARGS+="-i functional"
        fi
        # Likewise for dataplane tests
        REGEX="dataplane tests"
        if [[ "${gerritComment}" =~ \$REGEX ]]; then
          ROBOT_MISC_ARGS+="-i dataplane"
        fi

        make -C $WORKSPACE/voltha-system-tests ${makeTarget} || true
        """
      }
    }
  }
  post {
    always {
      // stop logging
      sh """
        P_IDS="\$(ps e -ww -A | grep "_TAG=kail-${workFlow}" | grep -v grep | awk '{print \$1}')"
        if [ -n "\$P_IDS" ]; then
          echo \$P_IDS
          for P_ID in \$P_IDS; do
            kill -9 \$P_ID
          done
        fi
        gzip $WORKSPACE/${workFlow}/onos-voltha-combined.log || true
      """
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: 'RobotLogs/log*.html',
        otherFiles: '',
        outputFileName: 'RobotLogs/output*.xml',
        outputPath: '.',
        passThreshold: 100,
        reportFileName: 'RobotLogs/report*.html',
        unstableThreshold: 0]);
      archiveArtifacts artifacts: '**/*.txt,**/*.gz,*.gz'
    }
  }
}

// refs/changes/06/24206/5
