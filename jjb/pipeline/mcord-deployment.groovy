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
                if [ -x "/usr/bin/helm" ]; then
                    helm list
                    helm delete --purge xos-core
                    helm delete --purge mcord
                    helm delete --purge base-openstack
                    helm delete --purge onos-cord

                    for NS in openstack ceph nfs libvirt; do
                       helm ls --namespace $NS --short | xargs -r -L1 -P2 helm delete --purge
                    done

                    # delete any helm chart left
                    helm ls --short | xargs -r -L1 -P2 helm delete --purge || true

                    #delete all kubectl pods
                    kubectl delete pods --all

                    sudo docker ps -aq | xargs -r -L1 -P16 sudo docker rm -f

                    sudo rm -rf /var/lib/openstack-helm/*

                    # NOTE(portdirect): These directories are used by nova and libvirt
                    sudo rm -rf /var/lib/nova/*
                    sudo rm -rf /var/lib/libvirt/*
                    sudo rm -rf /etc/libvirt/qemu/*

                    #remove all docker images
                    sudo docker rmi $(sudo docker images -q) || true

                    #remove ssh known hosts
                    sudo rm ~/.ssh/known_hosts
                fi
                kubectl get pods || true
                helm ls || true
                sudo rm -rf $WORKSPACE/*
                popd
                '''
            step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${notificationEmail}", sendToIndividuals: false])
     }
    }
}
