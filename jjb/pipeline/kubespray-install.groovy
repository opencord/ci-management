/*kubespray installation script test*/

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
        sh '''
            pushd $WORKSPACE/automation-tools/kubespray-installer
            ./setup.sh -i flex-onf-pod1 ${deployment_config.node1.ip} ${deployment_config.node2.ip} ${deployment_config.node3.ip}
            popd
            '''
            }
        }

    stage ('Validate Kube Config File') {
      steps {
        sh '''
            pushd $WORKSPACE/automation-tools/kubespray-installer/configs
            #Validate the conf file
            export KUBECONFIG=$WORKSPACE/automation-tools/kubespray-installer/configs${deployment_config.pod_config}
            kubectl get pods
            popd
            '''
            }
        }
    }

}
