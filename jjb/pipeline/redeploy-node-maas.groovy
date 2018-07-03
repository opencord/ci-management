/*re-deploy node via maas cli commands. to be used prior to mcord 6.0 build job*/

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  stages {

    stage ('Re-Deploy Node') {
      steps {
          sh "maas login maas http://${maas_ip}/MAAS/api/2.0 ${maas_api_key}"
          sh "maas maas machine release ${node_id}"

          timeout(time:15) {
            waitUntil {
              script {
                  try {
                    sh "maas maas machine read ${node_id} | grep Ready"
                    return true
                    } catch (exception) {
                        return false
                    }
                 }
              }
            }

            sh "maas maas machines allocate"
            sh "maas maas machine deploy ${node_id}"

         timeout(time:15) {
            waitUntil {
              script {
                  try {
                    sh "maas maas machine read ${node_id} | grep Deployed"
                    return true
                    } catch (exception) {
                        return false
                    }
                 }
              }
            }
          }
      }
   }

  post {
        always {
          step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${notificationEmail}", sendToIndividuals: false])
     }
    }
}
