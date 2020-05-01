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

// deploy VOLTHA built from patchset on a physical pod and run e2e test
// uses kind-voltha to deploy voltha-2.X

// Need this so that deployment_config has global scope when it's read later
deployment_config = null
localDeploymentConfigFile = null
localKindVolthaValuesFile = null
localSadisConfigFile = null

// The pipeline assumes these variables are always defined
if ( ! params.withPatchset ) {
  GERRIT_EVENT_COMMENT_TEXT = ""
  GERRIT_PROJECT = ""
  GERRIT_CHANGE_NUMBER = ""
  GERRIT_PATCHSET_NUMBER = ""
}

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 90, unit: 'MINUTES')
  }

  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="$WORKSPACE/voltha/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    TYPE="minimal"
    FANCY=0
    //VOL-2194 ONOS SSH and REST ports hardcoded to 30115/30120 in tests
    ONOS_SSH_PORT=30115
    ONOS_API_PORT=30120
  }

  stages {
    stage ('Initialize') {
      steps {
        sh returnStdout: false, script: """
        test -e $WORKSPACE/voltha/kind-voltha/voltha && cd $WORKSPACE/voltha/kind-voltha && ./voltha down
        cd $WORKSPACE
        rm -rf $WORKSPACE/*
        """
        script {
          if (env.configRepo && ! env.localConfigDir) {
            env.localConfigDir = "$WORKSPACE"
            sh returnStdout: false, script: "git clone -b master ${cordRepoUrl}/${configRepo}"
          }
          localDeploymentConfigFile = "${env.localConfigDir}/${params.deploymentConfigFile}"
          localKindVolthaValuesFile = "${env.localConfigDir}/${params.kindVolthaValuesFile}"
          localSadisConfigFile = "${env.localConfigDir}/${params.sadisConfigFile}"
        }
      }
    }

    stage('Repo') {
      steps {
        checkout(changelog: true,
          poll: false,
          scm: [$class: 'RepoScm',
            manifestRepositoryUrl: "${params.manifestUrl}",
            manifestBranch: "${params.manifestBranch}",
            currentBranch: true,
            destinationDir: 'voltha',
            forceSync: true,
            resetFirst: true,
            quiet: true,
            jobs: 4,
            showAllChanges: true]
          )
      }
    }

    stage('Patch') {
      steps {
        sh """
           pushd $WORKSPACE/
           echo "${gerritProject}" "${gerritChangeNumber}" "${gerritPatchsetNumber}"
           echo "${GERRIT_REFSPEC}"
           git clone https://gerrit.opencord.org/${gerritProject}
           cd "${gerritProject}"
           git fetch https://gerrit.opencord.org/${gerritProject} "${GERRIT_REFSPEC}" && git checkout FETCH_HEAD
           popd
           """
      }
    }

    stage('Check config files') {
      steps {
        script {
          try {
            deployment_config = readYaml file: "${localDeploymentConfigFile}"
          } catch (err) {
            echo "Error reading ${localDeploymentConfigFile}"
            throw err
          }
          sh returnStdout: false, script: """
          if [ ! -e ${localKindVolthaValuesFile} ]; then echo "${localKindVolthaValuesFile} not found"; exit 1; fi
          if [ ! -e ${localSadisConfigFile} ]; then echo "${localSadisConfigFile} not found"; exit 1; fi
          """
        }
      }
    }

    stage('Build olt addr list') {
      steps {
        script {
          sh """
          echo "ADDR_LIST:" > /tmp/robot_vars.yaml
          """
          deployment_config.olts.each { olt ->
            sh """
            echo " - ${olt.ip}:8888" >> /tmp/robot_vars.yaml
            """
          }
          sh """
          cat /tmp/robot_vars.yaml
          """
        }
      }
    }

    stage('Create KinD Cluster') {
      steps {
        sh returnStdout: false, script: """
        cd $WORKSPACE/voltha/kind-voltha/
        JUST_K8S=y ./voltha up
        """
      }
    }

    stage('Build Redfish Importer Image') {
      steps {
        sh """
           make -C $WORKSPACE/device-management/\$1 DOCKER_REPOSITORY=opencord/ DOCKER_TAG=citest docker-build-importer
           """
      }
    }

    stage('Build demo_test Image') {
      steps {
        sh """
           make -C $WORKSPACE/device-management/\$1/demo_test DOCKER_REPOSITORY=opencord/ DOCKER_TAG=citest docker-build
           """
      }
    }

    stage('Build mock-redfish-server  Image') {
      steps {
        sh """
           make -C $WORKSPACE/device-management/\$1/mock-redfish-server DOCKER_REPOSITORY=opencord/ DOCKER_TAG=citest docker-build
           """
      }
    }

    stage('Push Images') {
      steps {
        sh '''
             docker images | grep citest
             for image in \$(docker images -f "reference=*/*citest" --format "{{.Repository}}"); do echo "Pushing \$image to nodes"; kind load docker-image \$image:citest --name voltha-\$TYPE --nodes voltha-\$TYPE-worker,voltha-\$TYPE-worker2; done
           '''
      }
    }

    stage('Deploy Voltha') {
      environment {
        WITH_SIM_ADAPTERS="no"
        WITH_RADIUS="yes"
        DEPLOY_K8S="no"
        VOLTHA_LOG_LEVEL="DEBUG"
      }
      steps {
        script {
          sh returnStdout: false, script: """
          export EXTRA_HELM_FLAGS='--set log_agent.enabled=False -f ${localKindVolthaValuesFile} '

          cd $WORKSPACE/voltha/kind-voltha/
          echo \$EXTRA_HELM_FLAGS
          kail -n voltha -n default > $WORKSPACE/onos-voltha-combined.log &
          ./voltha up
          """
        }
      }
    }

    stage('Deploy Kafka Dump Chart') {
      steps {
        script {
          sh returnStdout: false, script: """
              helm repo add cord https://charts.opencord.org
              helm repo update
              helm install -n voltha-kafka-dump cord/voltha-kafka-dump
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
        kubectl cp $WORKSPACE/voltha/voltha-system-tests/tests/data/TechProfile-${profile}.json voltha/\$etcd_container:/tmp/flexpod.json
        kubectl exec -it \$etcd_container -n voltha -- /bin/sh -c 'cat /tmp/flexpod.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/XGS-PON/64'
        """
      }
    }

    stage('Push Sadis-config') {
      steps {
        sh returnStdout: false, script: """
        curl -sSL --user karaf:karaf -X POST -H Content-Type:application/json http://${deployment_config.nodes[0].ip}:$ONOS_API_PORT/onos/v1/network/configuration --data @${localSadisConfigFile}
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
            sh returnStdout: false, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service openolt stop' || true"
            sh returnStdout: false, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'killall dev_mgmt_daemon' || true"
            sh returnStdout: false, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'dpkg --remove asfvolt16 && dpkg --purge asfvolt16'"
            waitUntil {
              olt_sw_present = sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'dpkg --list | grep asfvolt16 | wc -l'"
              return olt_sw_present.toInteger() == 0
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
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service openolt stop' || true
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'killall dev_mgmt_daemon' || true
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'rm -f /var/log/openolt.log'
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'rm -f /var/log/dev_mgmt_daemon.log'
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service dev_mgmt_daemon start &'
            sleep 5
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service openolt start &'
            # restart redfish server
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service psme stop' || true
            sleep 10
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service psme start'
            sleep 10
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'ps auxw | grep -i psme'
            """
            //waitUntil {
            //  onu_discovered = sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'grep \"onu discover indication\" /var/log/openolt.log | wc -l'"
            //  return onu_discovered.toInteger() > 0
            //}
          }
        }
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           mkdir -p $WORKSPACE/RobotLogs
           # tell the kubernetes script to use images tagged citest and pullPolicy:Never
           sed -i 's/master/citest/g' $WORKSPACE/device-management/kubernetes/deploy-redfish-importer.yaml
           sed -i 's/imagePullPolicy: Always/imagePullPolicy: Never/g' $WORKSPACE/device-management/kubernetes/deploy-redfish-importer.yaml
           # passing a list to robot framework on the command line is hard
           make -C $WORKSPACE/device-management ROBOT_EXTRA_ARGS="-V /tmp/robot_vars.yaml"  functional-physical-test-single || true
           '''
      }
    }

    stage('After-Test Delay') {
      when {
        expression { params.withPatchset }
      }
      steps {
        sh returnStdout: false, script: """
        # Note: Gerrit comment text will be prefixed by "Patch set n:" and a blank line
        REGEX="hardware test with delay\$"
        [[ "$GERRIT_EVENT_COMMENT_TEXT" =~ \$REGEX ]] && sleep 10m || true
        """
      }
    }
  }

  post {
    always {
      sh returnStdout: false, script: '''
      set +e
      cp kind-voltha/install-minimal.log $WORKSPACE/
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
      kubectl get nodes -o wide
      kubectl get pods -o wide
      kubectl get pods -n voltha -o wide

      sync
      pkill kail || true

      ## Pull out errors from log files
      extract_errors_go() {
        echo
        echo "Error summary for $1:"
        grep $1 $WORKSPACE/onos-voltha-combined.log | grep '"level":"error"' | cut -d ' ' -f 2- | jq -r '.msg'
        echo
      }

      extract_errors_python() {
        echo
        echo "Error summary for $1:"
        grep $1 $WORKSPACE/onos-voltha-combined.log | grep 'ERROR' | cut -d ' ' -f 2-
        echo
      }

      extract_errors_go voltha-rw-core > $WORKSPACE/error-report.log
      extract_errors_go adapter-open-olt >> $WORKSPACE/error-report.log
      extract_errors_python adapter-open-onu >> $WORKSPACE/error-report.log
      extract_errors_python voltha-ofagent >> $WORKSPACE/error-report.log

      gzip $WORKSPACE/onos-voltha-combined.log

      ## collect events, the chart should be running by now
      kubectl get pods | grep -i voltha-kafka-dump | grep -i running
      if [[ $? == 0 ]]; then
         kubectl exec -it `kubectl get pods | grep -i voltha-kafka-dump | grep -i running | cut -f1 -d " "` ./voltha-dump-events.sh > $WORKSPACE/voltha-events.log
      fi
      '''
      script {
        deployment_config.olts.each { olt ->
          sh returnStdout: false, script: """
          sshpass -p ${olt.pass} scp ${olt.user}@${olt.ip}:/var/log/openolt.log $WORKSPACE/openolt-${olt.ip}.log || true
          sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/openolt-${olt.ip}.log  # Remove escape sequences
          sshpass -p ${olt.pass} scp ${olt.user}@${olt.ip}:/var/log/dev_mgmt_daemon.log $WORKSPACE/dev_mgmt_daemon-${olt.ip}.log || true
          sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/dev_mgmt_daemon-${olt.ip}.log  # Remove escape sequences
          """
        }
      }
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: 'device-management/demo_test/functional_test/log*.html',
        otherFiles: '',
        outputFileName: 'device-management/demo_test/functional_test/output*.xml',
        outputPath: '.',
        passThreshold: 100,
        reportFileName: 'device-management/demo_test/functional_test/report*.html',
        unstableThreshold: 0]);
      archiveArtifacts artifacts: '*.log,*.gz'
    }
  }
}
