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
            pushd /home/cord/
            git clone https://gerrit.opencord.org/automation-tools
            popd
            '''
            }
        }

    stage ('Install MCORD') {
      steps {
        sh '''
            pushd /home/cord/
            ./automation-tools/mcord/mcord-in-a-box.sh
            popd
            '''
            }
        }

    stage ('Test MCORD') {
      steps {
        sh '''
            pushd /home/cord/
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
                pushd /home/cord/
                kubectl get pods --all-namespaces
                helm list
                helm delete --purge xos-core
                helm delete --purge mcord
                helm delete --purge base-openstack
                sudo rm /usr/local/bin/kubectl
                helm reset --force
                popd
                '''
            step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "suchitra@opennetworking.org, you@opennetworking.org, kailash@opennetworking.org", sendToIndividuals: false])
     }
    }
}
