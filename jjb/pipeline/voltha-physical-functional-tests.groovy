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
    timeout(time: 60, unit: 'MINUTES')
  }

  environment {
    KUBECONFIG="$WORKSPACE/${configBaseDir}/${configKubernetesDir}/${configFileName}.conf"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$WORKSPACE/kind-voltha/bin"
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
        """
      }
    }
    stage('Subscriber Validation and Ping Tests') {
      environment {
        ROBOT_CONFIG_FILE="$WORKSPACE/${configBaseDir}/${configDeploymentDir}/${configFileName}.yaml"
        ROBOT_FILE="Voltha_PODTests.robot"
      }
      steps {
        sh """
        mkdir -p $WORKSPACE/RobotLogs
        if  ( ${released} ); then
            export ROBOT_MISC_ARGS="--removekeywords wuks -i released -i sanity -e bbsim -e notready -d $WORKSPACE/RobotLogs -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir}"
        else
            export ROBOT_MISC_ARGS="--removekeywords wuks -e bbsim -e notready -d $WORKSPACE/RobotLogs -v POD_NAME:${configFileName} -v KUBERNETES_CONFIGS_DIR:$WORKSPACE/${configBaseDir}/${configKubernetesDir}"
        fi
        make -C $WORKSPACE/voltha/voltha-system-tests voltha-test || true
        """
      }
    }
  }

  post {
    always {
      sh returnStdout: false, script: """
      set +e
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
      kubectl get nodes -o wide
      kubectl get pods -n voltha -o wide
      ## get default pod logs
      for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
      do
        if [[ \$pod == *"onos"* && \$pod != *"onos-service"* ]]; then
          kubectl logs \$pod onos> $WORKSPACE/\$pod.log;
        else
          kubectl logs \$pod> $WORKSPACE/\$pod.log;
        fi
      done
      ## get voltha pod logs
      for pod in \$(kubectl get pods --no-headers -n voltha | awk '{print \$1}');
      do
        if [[ \$pod == *"-api-"* ]]; then
          kubectl logs \$pod arouter -n voltha > $WORKSPACE/\$pod.log;
        else
          kubectl logs \$pod -n voltha > $WORKSPACE/\$pod.log;
        fi
      done
      """
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
        logFileName: 'RobotLogs/log*.html',
        otherFiles: '',
        outputFileName: 'RobotLogs/output*.xml',
        outputPath: '.',
        passThreshold: 100,
        reportFileName: 'RobotLogs/report*.html',
        unstableThreshold: 0
        ]);
      archiveArtifacts artifacts: '*.log'
    }
    unstable {
      step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${notificationEmail}", sendToIndividuals: false])
    }
  }
}
