/*re-deploy node then use mcord-in-a-box.sh to deploy MCORD and then E2E TEST*/

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  stages {

    stage ("Parse deployment configuration file") {
      steps {
            sh returnStdout: true, script: 'rm -rf ${configRepoBaseDir}'
            sh returnStdout: true, script: 'git clone -b ${branch} ${configRepoUrl}'
            script { deployment_config = readYaml file: "${configRepoBaseDir}${configRepoFile}" }
          }
    }

    stage ('Re-Deploy Node') {
      steps {
          sh "maas login maas http://${deployment_config.maas.ip}/MAAS/api/2.0 ${deployment_config.maas.api_key}"
          sh "maas maas machine release ${deployment_config.maas.head_system_id}"

          timeout(time:15) {
            waitUntil {
              script {
                  try {
                    sh "maas maas machine read ${deployment_config.maas.head_system_id} | grep Ready"
                    return true
                    } catch (exception) {
                        return false
                    }
                 }
              }
            }

            sh "maas maas machines allocate"
            sh "maas maas machine deploy ${deployment_config.maas.head_system_id}"

         timeout(time:15) {
            waitUntil {
              script {
                  try {
                    sh "maas maas machine read ${deployment_config.maas.head_system_id} | grep Deployed"
                    return true
                    } catch (exception) {
                        return false
                    }
                 }
              }
            }
          }
      }
    stage ('Get Node IP') {
      steps {
          sh "maas login maas http://${deployment_config.maas.ip}/MAAS/api/2.0 ${deployment_config.maas.api_key}"
          timeout(time:15) {
            waitUntil {
              script {
                  try {
                    node_ip = sh (script: "maas maas machine read ${deployment_config.maas.head_system_id} | grep ip_address | head -n1 | grep -oE '\\b([0-9]{1,3}\\.){3}[0-9]{1,3}\\b'", returnStdout: true).toString().trim()
                    return true
                    } catch (exception) {
                        return false
                    }
                 }
            }
          sh "echo ${node_ip}"
          }
      }
      }

    stage ('Checkout Automation-Tools Repo') {
      steps {
        script {
          sh (script: "ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} 'mkdir ~/cord; cd ~/cord/; git clone https://gerrit.opencord.org/automation-tools'", returnStdout: true)
          }
      }
    }

    stage ('Install MCORD') {
      options {
        timeout(time: 3, unit: 'HOURS')
        }
      steps {
        script {
          sh "sleep 120"
          sh "ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} 'cd ~/cord/; ./automation-tools/mcord/mcord-in-a-box.sh 1>&2'"
          }
      }
    }

    stage ('Configure K8 Compute Node DNS') {
      steps {
        sh """
            COMPUTEHOSTNAME=\$(ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} 'cat /etc/hostname')
            IPADDRESS=\$(ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} "ip route get 8.8.8.8 | head -1 | cut -d' ' -f8")
            cat <<EOF > /tmp/\$COMPUTEHOSTNAME-dns.yaml
            kind: Service
            apiVersion: v1
            metadata:
              name: \$COMPUTEHOSTNAME
              namespace: default
            spec:
              type: ExternalName
              externalName: \$IPADDRESS
EOF
            cat /tmp/\$COMPUTEHOSTNAME-dns.yaml | ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} "cat > /tmp/\$COMPUTEHOSTNAME-dns.yaml"
            ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} "kubectl create -f /tmp/\$COMPUTEHOSTNAME-dns.yaml 1>&2"
            """
            }
        }

    stage ('Test MCORD') {
      steps {
        script {
          sh "ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} 'cd ~/cord/; git clone https://gerrit.opencord.org/mcord; cd mcord/test; ansible-playbook -i localhost, -c local mcord-cavium-test-playbook.yml 1>&2'"
          }
       }
    }

   }

  post {
        always {
          script {
          sh (script: "ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} 'helm ls; kubectl get pods --all-namespaces 1>&2'", returnStdout: true)
          sh (script: "ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} 'export OS_CLOUD=openstack_helm; openstack image list 1>&2'", returnStdout: true)
          sh (script: "ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} 'export OS_CLOUD=openstack_helm; openstack network list 1>&2'", returnStdout: true)
          sh (script: "ssh -oStrictHostKeyChecking=no -i ~/.ssh/cord ubuntu@${node_ip} 'export OS_CLOUD=openstack_helm; openstack server list --all-projects 1>&2'", returnStdout: true)

          }
          step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${notificationEmail}", sendToIndividuals: false])
     }
    }
}
