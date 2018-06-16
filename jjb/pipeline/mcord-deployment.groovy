/* mcord-in-a-box 6.0 pipeline build and test*/

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  stages {

    stage ('Checkout Automation-Tools Repo') {
      steps {
        sh '''
            pushd $WORKSPACE
            git clone https://gerrit.opencord.org/automation-tools
            popd
            '''
            }
        }

    stage ('Install MCORD') {
      steps {
        sh '''
            pushd $WORKSPACE
            ./automation-tools/mcord/mcord-in-a-box.sh
            popd
            '''
            }
        }

    stage ('Test MCORD') {
      steps {
        sh '''
            pushd $WORKSPACE
            git clone https://gerrit.opencord.org/mcord
            cd mcord/test
            ansible-playbook -i localhost, mcord-cavium-test-playbook.yml
            popd
            '''
            }
        }
    }

    post {
        always {
            sh '''
                pushd $WORKSPACE
                kubectl get pods --all-namespaces
                if [ -x "/usr/bin/helm" ]; then
                    helm list
                    helm delete --purge xos-core
                    helm delete --purge mcord
                    helm delete --purge base-openstack
                    helm reset --force
                fi
                if [ -x "/usr/bin/kubelet" ]; then
                    sudo rm /usr/bin/kubelet
                fi
                popd
                '''
            step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${notificationEmail}", sendToIndividuals: false])
     }
    }
}
