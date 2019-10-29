/*kubespray installation script test*/

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  stages {

    stage ("Clean workspace") {
      steps {
            sh 'rm -rf *'
          }
        }

    stage ("Parse deployment configuration file") {
      steps {
            sh returnStdout: true, script: 'git clone -b ${branch} ${configRepoUrl}'
            script { deployment_config = readYaml file: "${configRepoBaseDir}${configRepoFile}" }
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

    stage ('Install Kubespray on Nodes') {
      steps {
        sh """
            pushd $WORKSPACE/automation-tools/kubespray-installer
            ./setup.sh -i ${podName} ${deployment_config.node1.ip} ${deployment_config.node2.ip} ${deployment_config.node3.ip}
            popd
            """
            }
        }

    stage ('Validate Kube Config File') {
      steps {
        sh """
            pushd $WORKSPACE/automation-tools/kubespray-installer
            ls -al
            source setup.sh -s ${podName}
            kubectl get pods
            popd
            """
            }
        }
    }

}
