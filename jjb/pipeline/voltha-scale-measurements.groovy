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
    SECONDS=0
    WITH_SIM_ADAPTERS="n"
    WITH_RADIUS="y"
    WITH_BBSIM="y"
    DEPLOY_K8S="y"
    VOLTHA_LOG_LEVEL="WARN"
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
          EXTRA_HELM_FLAGS="--set onu=${onuPerPon},pon=${ponPorts},delay=${BBSIMdelay}" ./voltha up
          '''
      }
    }
    stage('MIB-template') {
      steps {
        sh '''
          if [ ${withMibTemplate} = true ] ; then
            git clone https://github.com/opencord/voltha-openonu-adapter.git
            cat voltha-openonu-adapter/templates/BBSM-12345123451234512345-00000000000001-v1.json | kubectl exec -it -n voltha $(kubectl get pods -n voltha | grep etcd-cluster | awk 'NR==1{print $1') etcdctl put service/voltha/omci_mibs/templates/BBSM/12345123451234512345/00000000000001
            rm -rf voltha-openonu-adapter
          fi
        '''
      }
    }
    stage('disable-ONOS-apps') {
      steps {
         sh '''
          #Check withOnosApps and disable apps accordingly
          if [ ${withOnosApps} = false ] ; then
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost app deactivate org.opencord.olt
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost app deactivate org.opencord.aaa
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost app deactivate org.opencord.dhcpl2relay
          fi
         '''
      }
    }
    stage('configuration') {
      steps {
        sh '''
          #Setting LOG level to WARN
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost log:set WARN
          #kubectl exec -n voltha $(kubectl get pods -n voltha | grep bbsim | awk 'NR==1{print $1') bbsimctl log warn false
          #Setting link discovery
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost cfg set org.onosproject.provider.lldp.impl.LldpLinkProvider enabled ${setLinkDiscovery}
          #Setting the flow stats collection interval
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost cfg set org.onosproject.provider.of.flow.impl.OpenFlowRuleProvider flowPollFrequency ${flowStatInterval}
          #Setting the ports stats collection interval
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost cfg set org.onosproject.provider.of.device.impl.OpenFlowDeviceProvider portStatsPollFrequency ${portsStatInterval}
        '''
      }
    }
    stage('activate-ONUs') {
      steps {
        sh '''
          if [ -z ${expectedOnus} ]
          then
            echo -e "You need to set the target ONU number\n"
            exit 1
          fi

          voltctl device create -t openolt -H bbsim:50060
          voltctl device enable $(voltctl device list --filter Type~openolt -q)

          # check ONUs reached Active State in VOLTHA
          i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
          until [ $i -eq ${expectedOnus} ]
          do
            echo "$i ONUs ACTIVE of ${expectedOnus} expected (time: $SECONDS)"
            sleep ${pollInterval}
            i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
          done
          echo "${expectedOnus} ONUs Activated in $SECONDS seconds (time: $SECONDS)"
        '''
      }
    }
    stage('ONOS-ports') {
      steps {
        sh '''    
          # Check ports showed up in ONOS
          z=$(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost ports -e | grep BBSM | wc -l)
          until [ $z -eq ${expectedOnus} ]
          do
            echo "${z} enabled ports of ${expectedOnus} expected (time: $SECONDS)"
            sleep ${pollInterval}
            z=$(sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost ports -e | grep BBSM | wc -l)
          done
          echo "${expectedOnus} ports enabled in $SECONDS seconds (time: $SECONDS)"
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
