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
    timeout(time: 180, unit: 'MINUTES')
  }

  environment {
    KUBECONFIG="$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
  }

  stages {
    stage ('Initialize') {
      steps {
        step([$class: 'WsCleanup'])
        sh returnStdout: false, script: "git clone -b master ${cordRepoUrl}/${configBaseDir}"
        script {
          deployment_config = readYaml file: "${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
        }
        // This checkout allows us to show changes in Jenkins
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
        sh returnStdout: false, script: """
        cd voltha
        git clone -b master ${cordRepoUrl}/cord-tester
        mkdir -p $WORKSPACE/bin
        bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/bin"
        cd $WORKSPACE

        VC_VERSION=\$(curl -sSL https://api.github.com/repos/opencord/voltctl/releases/latest | jq -r .tag_name | sed -e 's/^v//g')
        HOSTOS=\$(uname -s | tr "[:upper:]" "[:lower:"])
        HOSTARCH=\$(uname -m | tr "[:upper:]" "[:lower:"])
        if [ \$HOSTARCH == "x86_64" ]; then
            HOSTARCH="amd64"
        fi
        curl -o $WORKSPACE/bin/voltctl -sSL https://github.com/opencord/voltctl/releases/download/v\${VC_VERSION}/voltctl-\${VC_VERSION}-\${HOSTOS}-\${HOSTARCH}
        chmod 755 $WORKSPACE/bin/voltctl
        voltctl version --clientonly

        # Remove noise from voltha-core logs
        voltctl loglevel set WARN read-write-core#github.com/opencord/voltha-go/db/model
        voltctl loglevel set WARN read-write-core#github.com/opencord/voltha-lib-go/v3/pkg/kafka
        # Remove noise from openolt logs
        voltctl loglevel set WARN adapter-open-olt#github.com/opencord/voltha-lib-go/v3/pkg/db
        voltctl loglevel set WARN adapter-open-olt#github.com/opencord/voltha-lib-go/v3/pkg/probe
        voltctl loglevel set WARN adapter-open-olt#github.com/opencord/voltha-lib-go/v3/pkg/kafka
        """
      }
    }

    stage('Functional Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
        ROBOT_FILE="Voltha_PODTests.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/FunctionalTests"
      }
      steps {
        sh """
        cd $WORKSPACE/voltha/kind-voltha/scripts
        ./log-collector.sh > /dev/null &
        ./log-combine.sh > /dev/null &

        mkdir -p $ROBOT_LOGS_DIR
        export ROBOT_MISC_ARGS="--removekeywords wuks -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
        make -C $WORKSPACE/voltha/voltha-system-tests voltha-test || true
        """
      }
    }

    stage('Failure/Recovery Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
        ROBOT_FILE="Voltha_FailureScenarios.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/FailureScenarios"
      }
      steps {
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        if ( ${powerSwitch} ); then
             export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functional -i PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
        else
             export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functional -e PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
        fi
        make -C $WORKSPACE/voltha/voltha-system-tests voltha-test || true
        """
      }
    }

    stage('Error Scenario Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
        ROBOT_FILE="Voltha_ErrorScenarios.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ErrorScenarios"
      }
      steps {
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functional -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
        make -C $WORKSPACE/voltha/voltha-system-tests voltha-test || true
        """
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

      sleep 60 # Wait for log-collector and log-combine to complete

      # Clean up "announcer" pod used by the tests if present
      kubectl delete pod announcer || true

      ## Pull out errors from log files
      extract_errors_go() {
        echo
        echo "Error summary for $1:"
        grep '"level":"error"' $WORKSPACE/voltha/kind-voltha/scripts/logger/combined/$1*
        echo
      }

      extract_errors_python() {
        echo
        echo "Error summary for $1:"
        grep 'ERROR' $WORKSPACE/voltha/kind-voltha/scripts/logger/combined/$1*
        echo
      }

      extract_errors_go voltha-rw-core > $WORKSPACE/error-report.log
      extract_errors_go adapter-open-olt >> $WORKSPACE/error-report.log
      extract_errors_python adapter-open-onu >> $WORKSPACE/error-report.log
      extract_errors_python voltha-ofagent >> $WORKSPACE/error-report.log
      extract_errors_python onos >> $WORKSPACE/error-report.log

      cd $WORKSPACE/voltha/kind-voltha/scripts/logger/combined/
      tar czf $WORKSPACE/container-logs.tgz *

      cd $WORKSPACE
      gzip *-combined.log || true
      '''
      script {
        deployment_config.olts.each { olt ->
          sh returnStdout: false, script: """
          sshpass -p ${olt.pass} scp ${olt.user}@${olt.ip}:/var/log/openolt.log $WORKSPACE/openolt-${olt.ip}.log || true
          sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/openolt-${olt.ip}.log  # Remove escape sequences
          """
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
        unstableThreshold: 0
        ]);
      archiveArtifacts artifacts: '*.log,*.gz,*.tgz'
    }
    unstable {
      step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${notificationEmail}", sendToIndividuals: false])
    }
  }
}
