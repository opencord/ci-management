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
    timeout(time: 90, unit: 'MINUTES')
  }

  environment {
    KUBECONFIG="$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$WORKSPACE/bin"
  }

  stages {
    stage ('Initialize') {
      steps {
        step([$class: 'WsCleanup'])
        sh returnStdout: false, script: "git clone -b ${branch} ${cordRepoUrl}/${configBaseDir}"
        script {
          deployment_config = readYaml file: "${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
        }
        // This checkout is just so that we can show changes in Jenkins
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
        sh returnStdout: false, script: """
        cd voltha
        git clone -b ${branch} ${cordRepoUrl}/cord-tester
        mkdir -p $WORKSPACE/bin
        bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/bin"
        cd $WORKSPACE
        git clone https://github.com/ciena/kind-voltha.git
        """
      }
    }
    stage('Subscriber Validation and Ping Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
        ROBOT_FILE="Voltha_PODTests.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/FunctionalTests"
      }
      steps {
        sh """
        cd $WORKSPACE/kind-voltha/scripts
        ./log-collector.sh > /dev/null &
        ./log-combine.sh > /dev/null &

        mkdir -p $ROBOT_LOGS_DIR
        if  ( ${released} ); then
            export ROBOT_MISC_ARGS="--removekeywords wuks -i released -i sanity -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
        else
            export ROBOT_MISC_ARGS="--removekeywords wuks -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
        fi
        make -C $WORKSPACE/voltha/voltha-system-tests voltha-test || true
        """
      }
    }

    stage('Failure/Recovery Tests') {
      when {
        expression { ! params.released }
      }
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
        ROBOT_FILE="Voltha_FailureScenarios.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/FailureScenarios"
      }
      steps {
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functional -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
        make -C $WORKSPACE/voltha/voltha-system-tests voltha-test || true
        """
      }
    }

    stage('Error Scenario Tests') {
      when {
        expression { ! params.released }
      }
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
        ROBOT_FILE="Voltha_ErrorScenarios.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/ErrorScenarios"
      }
      steps {
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functional -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
        make -C $WORKSPACE/voltha/voltha-system-tests voltha-test || true
        """
      }
    }
  }
  post {
    always {
      sh returnStdout: false, script: '''
      set +e
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
      kubectl get nodes -o wide
      kubectl get pods -n voltha -o wide

      sleep 60 # Wait for log-collector and log-combine to complete

      cd $WORKSPACE/kind-voltha/scripts/logger/combined/
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
