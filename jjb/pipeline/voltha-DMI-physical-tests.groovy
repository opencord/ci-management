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

// voltha-2.x e2e tests
// uses kind-voltha to deploy voltha-2.X
// uses bbsim to simulate OLT/ONUs

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 190, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/config"
    PATH="$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    ROBOT_MISC_ARGS="-e PowerSwitch ${params.extraRobotArgs}"
    LOG_FOLDER="$WORKSPACE/logs"
    APPS_TO_LOG="adtran-olt-device-manager"
    LOG_FOLDER="$WORKSPACE/logs"

  }
  stages {
    stage('Clone voltha-system-tests') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/voltha-system-tests",
            // refspec: "${volthaSystemTestsChange}"
          ]],
          branches: [[ name: "${branch}", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-system-tests"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
      }
    }

    //TODO deploy Kafka

    stage('Deploy DMI Container') {
      steps {
        sh """
           helm install ${params.extraHelmFlags} olt-device-manager ${params.dmi_chart}
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

    stage('Device Management Interface Tests') {
      environment {
        ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/DMITests"
      }
      steps {
        sh '''
           set +e

           export ROBOT_MISC_ARGS="-d $LOG_FOLDER"
           #Parametrize
           #uses https://github.com/opencord/voltha-system-tests/blob/master/tests/data/dmi-components-adtran.yaml
           # make -C $WORKSPACE/voltha-system-tests ${params.makeTarget} || true
           '''
      }
    }
  }

  post {
    always {
      sh '''

         cd $WORKSPACE/LOG_FOLDER
         gzip *-combined.log || true

         #TODO delete the device manager
         kubectl delete deployment voltctl || true
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: '**/log*.html',
            otherFiles: '',
            outputFileName: '**/output*.xml',
            outputPath: 'RobotLogs',
            passThreshold: 100,
            reportFileName: '**/report*.html',
            unstableThreshold: 0]);

         archiveArtifacts artifacts: '*.log,*.gz,*.tgz'

    }
  }
}
