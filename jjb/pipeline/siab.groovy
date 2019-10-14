/* seba-in-a-box build+test */

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }

  options {
      timeout(time: 1, unit: 'HOURS')
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
            make ${params.Test_Tags} ${params.version}
            popd
            """
            }
        }

    stage ('Run E2E Tests') {
      steps {
        sh """
            pushd $WORKSPACE/automation-tools/seba-in-a-box
            make ${params.Test_Target} ${params.Test_Tags} || true
            popd
            """
            }
        }

    stage ('Display Kafka Events') {
      steps {
        sh """
            pushd $WORKSPACE/automation-tools/seba-in-a-box
            CORD_KAFKA_IP=\$(kubectl get pod -l app=kafka -o json | jq -r '.items[0].status.podIP')
            kafkacat -e -C -b \$CORD_KAFKA_IP -t onu.events -f 'Topic %t [%p] at offset %o: key %k: %s\n >0'
            kafkacat -e -C -b \$CORD_KAFKA_IP -t authentication.events -f 'Topic %t [%p] at offset %o: key %k: %s\n >0'
            kafkacat -e -C -b \$CORD_KAFKA_IP -t dhcp.events -f 'Topic %t [%p] at offset %o: key %k: %s\n >0'
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
             kubectl describe pods > $WORKSPACE/kube_pods.log
             kubectl describe pods -n voltha > $WORKSPACE/kube_voltha_pods.log
             sudo cp /var/log/containers/*.log $WORKSPACE/
             sudo chown cord:cord $WORKSPACE/*log
             '''
             archiveArtifacts artifacts: '*.log'
             step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "andy@opennetworking.org, kailash@opennetworking.org", sendToIndividuals: false])
        }
        failure {
          sh '''
             curl -X GET -u karaf:karaf http://127.0.0.1:30120/onos/v1/devices
             curl -X GET -u karaf:karaf http://127.0.0.1:30120/onos/v1/devices/of:0000000000000001/ports
             curl -X GET http://127.0.0.1:30125/api/v1/devices
             curl -X GET http://127.0.0.1:30125/api/v1/logical_devices
             '''
        }
    }
}
