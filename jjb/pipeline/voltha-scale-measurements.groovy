/* voltha-scale-measurements pipeline */
pipeline {
  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="$WORKSPACE/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    TYPE="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="n"
    WITH_RADIUS="y"
    WITH_BBSIM="y"
    DEPLOY_K8S="y"
    VOLTHA_LOG_LEVEL="DEBUG"
    CONFIG_SADIS="n"
    ROBOT_MISC_ARGS="-d $WORKSPACE/RobotLogs -v teardown_device:False"
    SSHPASS="karaf"
  }

  stages {
    stage('checkout') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "https://github.com/ciena/kind-voltha.git", ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "kind-voltha"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        script {
          git_tags = sh(script:"cd kind-voltha; git tag -l --points-at HEAD", returnStdout: true).trim()
        }
      }
    }
    stage('start') {
      steps {
        sh '''
          #!/usr/bin/env bash
          set -euo pipefail
          '''
      }
    }

    stage('deploy-voltha') {
      steps {
        sh '''
          cd kind-voltha

          EXTRA_HELM_FLAGS="--set onu=${onuCount},pon=${ponCount}" ./voltha up

          '''
      }
    }

    stage('MIB-template') {
      steps {
        sh '''
          if [ "$withMibTemplate" = true ] ; then
            git clone https://github.com/opencord/voltha-openonu-adapter.git
            cat voltha-openonu-adapter/templates/BBSM-12345123451234512345-00000000000001-v1.json | kubectl exec -it -n voltha $(kubectl get pods -n voltha | grep etcd-cluster | awk 'NR==1{print $1}') etcdctl put service/voltha/omci_mibs/templates/BBSM/12345123451234512345/00000000000001
            rm -rf voltha-openonu-adapter
          fi
        '''
      }
    }

    stage('activate-ONUs') {
      steps {
        sh '''
          if [ -z "$onuTarget" ]
          then
            echo -e "You need to set the target ONU number\n"
            exit 1
          fi

          voltctl device create -t openolt -H bbsim:50060
          voltctl device enable $(voltctl device list --filter Type~openolt -q)
          SECONDS=0

          # check ONUs reached Active State in VOLTHA
          i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
          until [ $i -eq $onuTarget ]
          do
            echo "$i ONUs ACTIVE of $onuTarget expected (time: $SECONDS)"
            sleep $pollInterval
            i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
          done
          echo "$onuTarget ONUs Activated in $SECONDS seconds (time: $SECONDS)"
          #exit 0

        '''
      }
    }

    stage('disable-ONOS-apps') {
      steps {
         sh '''
          #Check withOnosApps and disable apps accordingly
          if [ "$withOnosApps" = false ] ; then
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost app deactivate org.opencord.olt
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost app deactivate org.opencord.aaa
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost app deactivate org.opencord.dhcpl2relay
          fi
         '''
      }
    }

    stage('ONOS-ports') {
      steps {
        sh '''    
          # Check ports showed up in ONOS
          z=$(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost ports -e | grep BBSM | wc -l)
          until [ $z -eq "$onuTarget" ]
          do
            echo "${z} enabled ports of "$onuTarget" expected (time: $SECONDS)"
            sleep $pollInterval
            z=$(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost ports -e | grep BBSM | wc -l)
          done
          echo "$onuTarget ports enabled in $SECONDS seconds (time: $SECONDS)"
        '''
      }
    }
  }
  post {
    cleanup {
      sh '''
        #!/usr/bin/env bash
        set -euo pipefail
        cd $WORKSPACE/kind-voltha

        WAIT_ON_DOWN=y ./voltha down
        cd $WORKSPACE/
        rm -rf kind-voltha/ || true
      '''
    }
  }
}
