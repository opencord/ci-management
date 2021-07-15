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

node {
  // Need this so that deployment_config has global scope when it's read later
  deployment_config = null
}

pipeline {
  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: 640, unit: 'MINUTES')
  }

  environment {
    KUBECONFIG="$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
  }

  stages {
    stage('Clone voltha-system-tests') {
      steps {
        step([$class: 'WsCleanup'])
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/voltha-system-tests",
            refspec: "${volthaSystemTestsChange}"
          ]],
          branches: [[ name: "${branch}", ]],
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
    stage ('Initialize') {
      steps {
        sh returnStdout: false, script: "git clone -b ${branch} ${cordRepoUrl}/${configBaseDir}"
        script {
           deployment_config = readYaml file: "${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
        }
        sh returnStdout: false, script: """
        mkdir -p $WORKSPACE/bin
        bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/bin"
        cd $WORKSPACE
        if [ "${params.branch}" == "voltha-2.8" ]; then
           VOLTCTL_VERSION=1.6.10
        else
           VOLTCTL_VERSION=\$(curl -sSL https://api.github.com/repos/opencord/voltctl/releases/latest | jq -r .tag_name | sed -e 's/^v//g')
        fi

        HOSTOS=\$(uname -s | tr "[:upper:]" "[:lower:"])
        HOSTARCH=\$(uname -m | tr "[:upper:]" "[:lower:"])
        if [ \$HOSTARCH == "x86_64" ]; then
            HOSTARCH="amd64"
        fi
        curl -o $WORKSPACE/bin/voltctl -sSL https://github.com/opencord/voltctl/releases/download/v\${VOLTCTL_VERSION}/voltctl-\${VOLTCTL_VERSION}-\${HOSTOS}-\${HOSTARCH}
        chmod 755 $WORKSPACE/bin/voltctl
        voltctl version --clientonly


        # Default kind-voltha config doesn't work on ONF demo pod for accessing kvstore.
        # The issue is that the mgmt node is also one of the k8s nodes and so port forwarding doesn't work.
        # We should change this. In the meantime here is a workaround.
        if [ "${params.branch}" == "master" ]; then
           set +e


        # Remove noise from voltha-core logs
           voltctl log level set WARN read-write-core#github.com/opencord/voltha-go/db/model
           voltctl log level set WARN read-write-core#github.com/opencord/voltha-lib-go/v3/pkg/kafka
        # Remove noise from openolt logs
           voltctl log level set WARN adapter-open-olt#github.com/opencord/voltha-lib-go/v3/pkg/db
           voltctl log level set WARN adapter-open-olt#github.com/opencord/voltha-lib-go/v3/pkg/probe
           voltctl log level set WARN adapter-open-olt#github.com/opencord/voltha-lib-go/v3/pkg/kafka
        fi
        """
      }
    }

    stage('Functional Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
        ROBOT_FILE="Voltha_DT_PODTests.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/dt-workflow/FunctionalTests"
      }
      steps {
        startComponentsLogs(logsDir: "$WORKSPACE/logs/FunctionalTests")
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        if ( ${powerSwitch} ); then
             export ROBOT_MISC_ARGS="--removekeywords wuks -i PowerSwitch -i sanityDt -i functionalDt -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
        else
             export ROBOT_MISC_ARGS="--removekeywords wuks -e PowerSwitch -i sanityDt -i functionalDt -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
        fi
        make -C $WORKSPACE/voltha-system-tests voltha-dt-test || true
        """
        stopComponentsLogs(logsDir: "$WORKSPACE/logs/FunctionalTests", compress: true)
      }
    }

    stage('Failure/Recovery Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
        ROBOT_FILE="Voltha_DT_FailureScenarios.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/dt-workflow/FailureScenarios"
      }
      steps {
        startComponentsLogs(logsDir: "$WORKSPACE/logs/FailureScenarios")
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        if ( ${powerSwitch} ); then
             export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functionalDt -i PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
        else
             export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functionalDt -e PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
        fi
        make -C $WORKSPACE/voltha-system-tests voltha-dt-test || true
        """
        stopComponentsLogs(logsDir: "$WORKSPACE/logs/FailureScenarios", compress: true)
      }
    }

    stage('Dataplane Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
        ROBOT_FILE="Voltha_DT_PODTests.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/dt-workflow/DataplaneTests"
      }
      steps {
        startComponentsLogs(logsDir: "$WORKSPACE/logs/DataplaneTests")
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        export ROBOT_MISC_ARGS="--removekeywords wuks -i dataplaneDt -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
        make -C $WORKSPACE/voltha-system-tests voltha-dt-test || true
        """
        stopComponentsLogs(logsDir: "$WORKSPACE/logs/DataplaneTests", compress: true)
      }
    }
    stage('HA Tests') {
       environment {
       ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
       ROBOT_FILE="Voltha_ONOSHATests.robot"
       ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ONOSHAScenarios"
      }
      steps {
        startComponentsLogs(logsDir: "$WORKSPACE/logs/ONOSHAScenarios")
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v workflow:${params.workFlow} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
        make -C $WORKSPACE/voltha-system-tests voltha-test || true
        """
        stopComponentsLogs(logsDir: "$WORKSPACE/logs/ONOSHAScenarios", compress: true)
      }
    }

    stage('Multiple OLT Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
        ROBOT_FILE="Voltha_DT_MultiOLT_Tests.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/dt-workflow/MultipleOLTScenarios"
      }
      steps {
        startComponentsLogs(logsDir: "$WORKSPACE/logs/ONOSHAScenarios")
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        if ( ${powerSwitch} ); then
             export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functionalDt -i PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
        else
             export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functionalDt -e PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
        fi
        make -C $WORKSPACE/voltha-system-tests voltha-dt-test || true
        """
        stopComponentsLogs(logsDir: "$WORKSPACE/logs/ONOSHAScenarios", compress: true)
      }
    }


    stage('Error Scenario Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
        ROBOT_FILE="Voltha_ErrorScenarios.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/dt-workflow/ErrorScenarios"
      }
      steps {
        startComponentsLogs(logsDir: "$WORKSPACE/logs/ErrorScenarios")
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functional -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v workflow:${params.workFlow} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
        make -C $WORKSPACE/voltha-system-tests voltha-test || true
        """
        stopComponentsLogs(logsDir: "$WORKSPACE/logs/ErrorScenarios", compress: true)
      }
    }
  }
  post {
    always {
      sh returnStdout: false, script: '''
      set +e
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq
      kubectl get nodes -o wide
      kubectl get pods -n voltha -o wide
      kubectl get pods -o wide

      # store information on running charts
      helm ls > $WORKSPACE/helm-list.txt || true

      # store information on the running pods
      kubectl get pods --all-namespaces -o wide > $WORKSPACE/pods.txt || true
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq | tee $WORKSPACE/pod-images.txt || true
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq | tee $WORKSPACE/pod-imagesId.txt || true
      '''
      script {
        deployment_config.olts.each { olt ->
            if (olt.type == null || olt.type == "" || olt.type == "openolt") {
              sh returnStdout: false, script: """
              sshpass -p ${olt.pass} scp -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no ${olt.user}@${olt.sship}:/var/log/openolt.log $WORKSPACE/openolt-${olt.sship}.log || true
              sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/openolt-${olt.sship}.log  # Remove escape sequences
              sshpass -p ${olt.pass} scp -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no ${olt.user}@${olt.sship}:/var/log/dev_mgmt_daemon.log $WORKSPACE/dev_mgmt_daemon-${olt.sship}.log || true
              sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/dev_mgmt_daemon-${olt.sship}.log  # Remove escape sequences
              sshpass -p ${olt.pass} scp -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no ${olt.user}@${olt.sship}:/var/log/startup.log $WORKSPACE/startup-${olt.sship}.log || true
              sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/startup-${olt.sship}.log || true # Remove escape sequences
              sshpass -p ${olt.pass} scp -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no ${olt.user}@${olt.sship}:/var/log/openolt_process_watchdog.log $WORKSPACE/openolt_process_watchdog-${olt.sship}.log || true
              sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/openolt_process_watchdog-${olt.sship}.log || true # Remove escape sequences
              """
            }
        }
      }
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: '**/log*.html',
        otherFiles: '',
        outputFileName: '**/output*.xml',
        outputPath: 'RobotLogs',
        passThreshold: 100,
        reportFileName: '**/report*.html',
        unstableThreshold: 0,
        onlyCritical: true
        ]);
      archiveArtifacts artifacts: '**/*.log,**/*.tgz,*.txt'
    }
  }
}
