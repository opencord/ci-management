// Copyright 2017-2022 Open Networking Foundation (ONF) and the ONF Contributors
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

library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://gerrit.opencord.org/ci-management.git'
])

node {
  // Need this so that deployment_config has global scope when it's read later
  deployment_config = null
}

def infraNamespace = "infra"
def volthaNamespace = "voltha"

pipeline {
  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: "${timeout}", unit: 'MINUTES')
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
        sh returnStdout: false, script: "git clone -b ${branch} ${cordRepoUrl}/${configBaseDir}"
        script {
           deployment_config = readYaml file: "${configBaseDir}/${configDeploymentDir}/${configFileName}-TT.yaml"
        }
        installVoltctl("${branch}")
        sh returnStdout: false, script: """
        mkdir -p $WORKSPACE/bin
        # download kail
        bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/bin"

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
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-TT.yaml"
        ROBOT_FILE="Voltha_TT_PODTests.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/tt-workflow/FunctionalTests"
      }
      steps {
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        if ( ${powerSwitch} ); then
             export ROBOT_MISC_ARGS="--removekeywords wuks -i functionalTT -i PowerSwitch -i sanityTT -i sanityTT-MCAST -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
             if ( ${powerCycleOlt} ); then
                  ROBOT_MISC_ARGS+=" -v power_cycle_olt:True"
             fi
        else
             export ROBOT_MISC_ARGS="--removekeywords wuks -i functionalTT -e PowerSwitch -i sanityTT -i sanityTT-MCAST -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
        fi
        ROBOT_MISC_ARGS+=" -v NAMESPACE:${volthaNamespace} -v INFRA_NAMESPACE:${infraNamespace}"
        make -C $WORKSPACE/voltha-system-tests voltha-tt-test || true
        """
      }
    }

    stage('Failure/Recovery Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-TT.yaml"
        ROBOT_FILE="Voltha_TT_FailureScenarios.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/tt-workflow/FailureScenarios"
      }
      steps {
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        if [ ${params.enableMultiUni} = false ]; then
          if ( ${powerSwitch} ); then
               export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functionalTT -i PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
          else
               export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functionalTT -e PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel}"
          fi
          ROBOT_MISC_ARGS+=" -v NAMESPACE:${volthaNamespace} -v INFRA_NAMESPACE:${infraNamespace}"
          make -C $WORKSPACE/voltha-system-tests voltha-tt-test || true
        fi
        """
      }
    }

    stage('Multi-Tcont Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-TT.yaml"
        ROBOT_FILE="Voltha_TT_MultiTcontTests.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/tt-workflow/MultiTcontScenarios"
        ROBOT_TEST_INPUT_FILE="$WORKSPACE/voltha-system-tests/tests/data/${configFileName}-TT-multi-tcont-tests-input.yaml"
      }
      steps {
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        if [ ${params.enableMultiUni} = false ]; then
          if ( ${powerSwitch} ); then
            export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functionalTT -i PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v teardown_device:False -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel} -V $ROBOT_TEST_INPUT_FILE"
          else
            export ROBOT_MISC_ARGS="--removekeywords wuks -L TRACE -i functionalTT -e PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v teardown_device:False -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel} -V $ROBOT_TEST_INPUT_FILE"
          fi
          ROBOT_MISC_ARGS+=" -v NAMESPACE:${volthaNamespace} -v INFRA_NAMESPACE:${infraNamespace}"
          make -C $WORKSPACE/voltha-system-tests voltha-tt-test || true
        fi
        """
      }
    }

    stage('Multicast Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}-TT.yaml"
        ROBOT_FILE="Voltha_TT_MulticastTests.robot"
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/tt-workflow/MulticastTests"
        ROBOT_TEST_INPUT_FILE="$WORKSPACE/voltha-system-tests/tests/data/${configFileName}-TT-multicast-tests-input.yaml"
      }
      steps {
        sh """
        mkdir -p $ROBOT_LOGS_DIR
        if [ ${params.enableMultiUni} = true ]; then
          if ( ${powerSwitch} ); then
             export ROBOT_MISC_ARGS="--removekeywords wuks -i multicastTT -i PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v teardown_device:False -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel} -V $ROBOT_TEST_INPUT_FILE"
          else
             export ROBOT_MISC_ARGS="--removekeywords wuks -i multicastTT -e PowerSwitch -e bbsim -e notready -d $ROBOT_LOGS_DIR -v teardown_device:False -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir} -v container_log_dir:$WORKSPACE -v OLT_ADAPTER_APP_LABEL:${oltAdapterAppLabel} -V $ROBOT_TEST_INPUT_FILE"
          fi
          ROBOT_MISC_ARGS+=" -v NAMESPACE:${volthaNamespace} -v INFRA_NAMESPACE:${infraNamespace}"
          make -C $WORKSPACE/voltha-system-tests voltha-tt-test || true
        fi
        """
      }
    }

  }
  post {
    always {
      getPodsInfo("$WORKSPACE/pods")
      sh returnStdout: false, script: '''
      set +e

      # collect logs collected in the Robot Framework StartLogging keyword
      cd $WORKSPACE
      gzip *-combined.log || true
      rm *-combined.log || true
      '''
      script {
        deployment_config.olts.each { olt ->
          if (olt.type == null || olt.type == "" || olt.type == "openolt") {
             sh returnStdout: false, script: """
             sshpass -p ${olt.pass} scp ${olt.user}@${olt.sship}:/var/log/openolt.log $WORKSPACE/openolt-${olt.sship}.log || true
             sshpass -p ${olt.pass} scp ${olt.user}@${olt.sship}:/var/log/dev_mgmt_daemon.log $WORKSPACE/dev_mgmt_daemon-${olt.sship}.log || true
             sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/openolt-${olt.sship}.log  # Remove escape sequences
             sed -i 's/\\x1b\\[[0-9;]*[a-zA-Z]//g' $WORKSPACE/dev_mgmt_daemon-${olt.sship}.log  # Remove escape sequences
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
      archiveArtifacts artifacts: '**/*.log,**/*.gz,**/*.tgz,*.txt,pods/*.txt'
    }
  }
}
