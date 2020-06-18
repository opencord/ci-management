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
if ( params.manualBranch != "" ) {
  GERRIT_EVENT_COMMENT_TEXT = ""
  GERRIT_PROJECT = ""
  GERRIT_BRANCH = "${params.manualBranch}"
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
            manifestBranch: "${params.branch}",
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

    stage('Get Patch') {
      when {
        expression { params.manualBranch == "" }
      }
      steps {
        sh returnStdout: false, script: """
        cd voltha
        repo download "${gerritProject}" "${gerritChangeNumber}/${gerritPatchsetNumber}"
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

    stage('Create KinD Cluster') {
      steps {
        sh returnStdout: false, script: """
        if [ "${branch}" != "master" ]; then
          echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
          source "$WORKSPACE/voltha/kind-voltha/releases/${branch}"
        else
          echo "on master, using default settings for kind-voltha"
        fi

        cd $WORKSPACE/voltha/kind-voltha/
        JUST_K8S=y ./voltha up
        """
      }
    }

    stage('Build and Push Images') {
      when {
        expression { params.manualBranch == "" }
      }
      steps {
        sh returnStdout: false, script: """

        if [ "${branch}" != "master" ]; then
          echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
          source "$WORKSPACE/voltha/kind-voltha/releases/${branch}"
        else
          echo "on master, using default settings for kind-voltha"
        fi

        if ! [[ "${gerritProject}" =~ ^(voltha-system-tests|kind-voltha|voltha-helm-charts)\$ ]]; then
          make -C $WORKSPACE/voltha/${gerritProject} DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
          docker images | grep citest
          for image in \$(docker images -f "reference=*/*citest" --format "{{.Repository}}")
          do
            echo "Pushing \$image to nodes"
            kind load docker-image \$image:citest --name voltha-\$TYPE --nodes voltha-\$TYPE-worker,voltha-\$TYPE-worker2
            docker rmi \$image:citest \$image:latest || true
          done
        fi
        """
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
          if [ "${branch}" != "master" ]; then
            echo "on branch: ${branch}, sourcing kind-voltha/releases/${branch}"
            source "$WORKSPACE/voltha/kind-voltha/releases/${branch}"
          else
            echo "on master, using default settings for kind-voltha"
          fi

          export EXTRA_HELM_FLAGS+='--set log_agent.enabled=False -f ${localKindVolthaValuesFile} '

          IMAGES=""
          if [ "${gerritProject}" = "voltha-go" ]; then
              IMAGES="rw_core ro_core "
          elif [ "${gerritProject}" = "ofagent-py" ]; then
              IMAGES="ofagent "
          elif [ "${gerritProject}" = "voltha-onos" ]; then
              IMAGES="onos "
          elif [ "${gerritProject}" = "voltha-openolt-adapter" ]; then
              IMAGES="adapter_open_olt "
          elif [ "${gerritProject}" = "voltha-openonu-adapter" ]; then
              IMAGES="adapter_open_onu "
          elif [ "${gerritProject}" = "voltha-api-server" ]; then
              IMAGES="afrouter afrouterd "
          else
              echo "No images to push"
          fi

          for I in \$IMAGES
          do
              EXTRA_HELM_FLAGS+="--set images.\$I.tag=citest,images.\$I.pullPolicy=Never "
          done

          if [ "${gerritProject}" = "voltha-helm-charts" ]; then
              export CHART_PATH=$WORKSPACE/voltha/voltha-helm-charts
              export VOLTHA_CHART=\$CHART_PATH/voltha
              export VOLTHA_ADAPTER_OPEN_OLT_CHART=\$CHART_PATH/voltha-adapter-openolt
              export VOLTHA_ADAPTER_OPEN_ONU_CHART=\$CHART_PATH/voltha-adapter-openonu
              helm dep update \$VOLTHA_CHART
              helm dep update \$VOLTHA_ADAPTER_OPEN_OLT_CHART
              helm dep update \$VOLTHA_ADAPTER_OPEN_ONU_CHART
          fi

          cd $WORKSPACE/voltha/kind-voltha/
          echo \$EXTRA_HELM_FLAGS
          kail -n voltha -n default > $WORKSPACE/onos-voltha-combined.log &
          ./voltha up

          set +e

          # Remove noise from voltha-core logs
          voltctl log level set WARN read-write-core#github.com/opencord/voltha-go/db/model
          voltctl log level set WARN read-write-core#github.com/opencord/voltha-lib-go/v3/pkg/kafka
          # Remove noise from openolt logs
          voltctl log level set WARN adapter-open-olt#github.com/opencord/voltha-lib-go/v3/pkg/db
          voltctl log level set WARN adapter-open-olt#github.com/opencord/voltha-lib-go/v3/pkg/probe
          voltctl log level set WARN adapter-open-olt#github.com/opencord/voltha-lib-go/v3/pkg/kafka
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
      environment {
        ROBOT_CONFIG_FILE="${localDeploymentConfigFile}"
        ROBOT_MISC_ARGS="${params.extraRobotArgs} --removekeywords wuks -d $WORKSPACE/RobotLogs -v container_log_dir:$WORKSPACE "
        ROBOT_FILE="Voltha_PODTests.robot"
      }
      steps {
        sh returnStdout: false, script: """
        cd voltha
        mkdir -p $WORKSPACE/RobotLogs

        # If the Gerrit comment contains a line with "functional tests" then run the full
        # functional test suite.  This covers tests tagged either 'sanity' or 'functional'.
        # Note: Gerrit comment text will be prefixed by "Patch set n:" and a blank line
        REGEX="functional tests"
        if [[ "$GERRIT_EVENT_COMMENT_TEXT" =~ \$REGEX ]]; then
          ROBOT_MISC_ARGS+="-i functional"
        fi

        make -C $WORKSPACE/voltha/voltha-system-tests voltha-test || true
        """
      }
    }

    stage('After-Test Delay') {
      when {
        expression { params.manualBranch == "" }
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
      cp $WORKSPACE/voltha/kind-voltha/install-minimal.log $WORKSPACE/
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq
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
          until sshpass -p ${olt.pass} scp ${olt.user}@${olt.ip}:/var/log/openolt.log $WORKSPACE/openolt-${olt.ip}.log
          do
              echo "Fetching openolt.log log failed, retrying..."
              sleep 10
          done
          sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/openolt-${olt.ip}.log  # Remove escape sequences
          until sshpass -p ${olt.pass} scp ${olt.user}@${olt.ip}:/var/log/dev_mgmt_daemon.log $WORKSPACE/dev_mgmt_daemon-${olt.ip}.log
          do
              echo "Fetching dev_mgmt_daemon.log failed, retrying..."
              sleep 10
          done
          sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/dev_mgmt_daemon-${olt.ip}.log  # Remove escape sequences
          """
        }
      }
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: 'RobotLogs/log*.html',
        otherFiles: '',
        outputFileName: 'RobotLogs/output*.xml',
        outputPath: '.',
        passThreshold: 100,
        reportFileName: 'RobotLogs/report*.html',
        unstableThreshold: 0]);
      archiveArtifacts artifacts: '*.log,*.gz'
    }
  }
}
