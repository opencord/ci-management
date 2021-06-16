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
    timeout(time: 280, unit: 'MINUTES')
  }

  environment {
    KUBECONFIG="$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
  }


  stages {
    stage('Clone kind-voltha') {
      steps {
        step([$class: 'WsCleanup'])
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/kind-voltha",
            refspec: "${kindVolthaChange}"
          ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "kind-voltha"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
      }
    }
    stage('Clone voltha-system-tests') {
      steps {
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
    stage('Clone cord-tester') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/cord-tester",
            refspec: "${cordTesterChange}"
          ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "cord-tester"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
      }
    }
    // This checkout allows us to show changes in Jenkins
    // we only do this on master as we don't branch all the repos for all the releases
    // (we should compute the difference by tracking the container version, not the code)
    stage('Download All the VOLTHA repos') {
      when {
        expression {
          return "${branch}" == 'master';
        }
      }
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
    stage ('Initialize') {
      steps {
        sh returnStdout: false, script: "git clone -b master ${cordRepoUrl}/${configBaseDir}"
        script {
           deployment_config = readYaml file: "${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
        }
        sh returnStdout: false, script: """
        mkdir -p $WORKSPACE/bin
        bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/bin"
        cd $WORKSPACE
        if [ "${params.branch}" != "master" ]; then
           cd $WORKSPACE/kind-voltha
           source releases/${params.branch}
           VC_VERSION=1.1.8
        else
           VC_VERSION=\$(curl -sSL https://api.github.com/repos/opencord/voltctl/releases/latest | jq -r .tag_name | sed -e 's/^v//g')
        fi

        HOSTOS=\$(uname -s | tr "[:upper:]" "[:lower:"])
        HOSTARCH=\$(uname -m | tr "[:upper:]" "[:lower:"])
        if [ \$HOSTARCH == "x86_64" ]; then
            HOSTARCH="amd64"
        fi
        curl -o $WORKSPACE/bin/voltctl -sSL https://github.com/opencord/voltctl/releases/download/v\${VC_VERSION}/voltctl-\${VC_VERSION}-\${HOSTOS}-\${HOSTARCH}
        chmod 755 $WORKSPACE/bin/voltctl
        voltctl version --clientonly

        if [ "${params.branch}" == "master" ]; then
        # Default kind-voltha config doesn't work on ONF demo pod for accessing kvstore.
        # The issue is that the mgmt node is also one of the k8s nodes and so port forwarding doesn't work.
        # We should change this. In the meantime here is a workaround.
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
        sh """
        cd $WORKSPACE/kind-voltha/scripts
        ./log-collector.sh > /dev/null &
        ./log-combine.sh > /dev/null &

        mkdir -p $ROBOT_LOGS_DIR
        if [ "${params.testType}" == "Functional" ]; then
            if ( ${powerSwitch} ); then
                 export ROBOT_MISC_ARGS="--removekeywords wuks -i PowerSwitch -i soak -e dataplaneDt -e bbsim -e notready -d $ROBOT_LOGS_DIR -v SOAK_TEST:True -v logging:False -v teardown_device:False -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
            else
                 export ROBOT_MISC_ARGS="--removekeywords wuks -e PowerSwitch -i soak -e dataplaneDt -e bbsim -e notready -d $ROBOT_LOGS_DIR -v SOAK_TEST:True -v logging:False -v teardown_device:False -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
            fi
            make -C $WORKSPACE/voltha-system-tests voltha-dt-test || true
        fi
        """
      }
    }

    stage('Failure/Recovery Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
        ROBOT_FILE="Voltha_DT_FailureScenarios.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/dt-workflow/FailureScenarios"
      }
      steps {
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        if [ "${params.testType}" == "Failure" ]; then
           export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i soak -e PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v SOAK_TEST:True -v logging:False -v teardown_device:False -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
           make -C $WORKSPACE/voltha-system-tests voltha-dt-test || true
        fi
        """
      }
    }

    stage('Dataplane Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-DT.yaml"
        ROBOT_FILE="Voltha_DT_PODTests.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/dt-workflow/DataplaneTests"
      }
      steps {
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        if [ "${params.testType}" == "Dataplane" ]; then
           export ROBOT_MISC_ARGS="--removekeywords wuks -i soakDataplane -e bbsim -e notready -d $ROBOT_LOGS_DIR -v SOAK_TEST:True -v logging:False -v teardown_device:False -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE"
           make -C $WORKSPACE/voltha-system-tests voltha-dt-test || true
        fi
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
        grep '"level":"error"' $WORKSPACE/kind-voltha/scripts/logger/combined/$1*
        echo
      }

      extract_errors_python() {
        echo
        echo "Error summary for $1:"
        grep 'ERROR' $WORKSPACE/kind-voltha/scripts/logger/combined/$1*
        echo
      }

      extract_errors_go voltha-rw-core > $WORKSPACE/error-report.log
      extract_errors_go adapter-open-olt >> $WORKSPACE/error-report.log
      extract_errors_python adapter-open-onu >> $WORKSPACE/error-report.log
      extract_errors_python voltha-ofagent >> $WORKSPACE/error-report.log
      extract_errors_python onos >> $WORKSPACE/error-report.log

      cd $WORKSPACE/kind-voltha/scripts/logger/combined/
      tar czf $WORKSPACE/container-logs.tgz *

      cd $WORKSPACE
      gzip *-combined.log || true

      # collect ETCD cluster logs
      mkdir -p $WORKSPACE/etcd
      printf '%s\n' $(kubectl get pods -l app=etcd -o=jsonpath="{.items[*]['metadata.name']}") | xargs -I% bash -c "kubectl logs % > $WORKSPACE/etcd/%.log"
      '''
      script {
        deployment_config.olts.each { olt ->
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
      archiveArtifacts artifacts: '*.log,*.gz,*.tgz,etcd/*.log'
    }
    unstable {
      step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${notificationEmail}", sendToIndividuals: false])
    }
  }
}
