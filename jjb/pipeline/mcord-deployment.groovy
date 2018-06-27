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
            mkdir cord
            cd cord/
            git clone https://gerrit.opencord.org/automation-tools
            popd
            '''
            }
        }

    stage ('Install MCORD') {
      steps {
        sh '''
            pushd $WORKSPACE/cord
            ./automation-tools/mcord/mcord-in-a-box.sh
            popd
            '''
            }
        }
    stage ('Configure K8 Compute Node DNS') {
      steps {
        sh '''
            pushd $WORKSPACE
            HOSTNAME=\$(cat /etc/hostname)
            IPADDRESS=\$(ip route get 8.8.8.8 | head -1 | cut -d' ' -f8)
            cat <<EOF > /tmp/\$HOSTNAME-dns.yaml
            kind: Service
            apiVersion: v1
            metadata:
              name: \$HOSTNAME
              namespace: default
            spec:
              type: ExternalName
              externalName: \$IPADDRESS
EOF
            popd
            kubectl create -f /tmp/\$HOSTNAME-dns.yaml
            '''
            }
        }

    stage ('Test MCORD') {
      steps {
        sh '''
            pushd $WORKSPACE
            git clone https://gerrit.opencord.org/mcord
            cd mcord/test
            ansible-playbook -i localhost, -c local mcord-cavium-test-playbook.yml
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
                helm list
                popd
                '''
            step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${notificationEmail}", sendToIndividuals: false])
     }
    }
}
