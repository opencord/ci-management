/* seba-in-a-box build+test */

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  stages {

    stage ("Clean workspace") {
      steps {
            sh 'rm -rf *'
          }
        }

    stage ('Checkout Automation-Tools Repo') {
      steps {
        sh '''
            pushd $WORKSPACE
            git clone https://gerrit.opencord.org/automation-tools
            popd
            '''
            }
        }

    stage ('Reset Kubeadm') {
      steps {
        sh """
            pushd $WORKSPACE/automation-tools/seba-in-a-box
            make reset-kubeadm
            popd
            """
            }
        }

    stage ('Install SEBA') {
      steps {
        sh """
            pushd $WORKSPACE/automation-tools/seba-in-a-box
            make -j2
            popd
            """
            }
        }

    stage ('Run E2E Tests') {
      steps {
        sh """
            pushd $WORKSPACE/automation-tools/seba-in-a-box
            make run-tests || true
            popd
            """
            }
        }

     stage('Publish') {
      steps {
        sh """
           if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
           cp -r $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/WorkflowValidations/*ml ./RobotLogs
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
      }
    }

  }

    post {
        always {
          sh '''
             sudo cp /var/log/containers/*.log $WORKSPACE/
             sudo chown cord:cord $WORKSPACE/*log
             '''
             archiveArtifacts artifacts: '*.log'
             step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "andy@opennetworking.org, kailash@opennetworking.org", sendToIndividuals: false])
        }
    }
}
