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
    VOLTHA_LOG_LEVEL="WARN"
    CONFIG_SADIS="n"
    ROBOT_MISC_ARGS="-d $WORKSPACE/RobotLogs -v teardown_device:False"
    SSHPASS="karaf"
    DEPLOY_K8S="n"
    EXTRA_HELM_FLAGS="--set onu=${onuPerPon},pon=${ponPorts},delay=${BBSIMdelay},auth=${bbsimAuth},dhcp=${bbsimDhcp}"
  }
  stages {
    stage('set-description') {
      steps {
        script {
          currentBuild.description = "${onuPerPon} ONU x ${ponPorts} PON"
        }
      }
    }
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
          if [ ! -z ${bbsimImg} ];
          then
            IFS=: read -r bbsimRepo bbsimTag <<< ${bbsimImg}
            EXTRA_HELM_FLAGS+=",images.bbsim.repository=${bbsimRepo},images.bbsim.tag=${bbsimTag}"
          fi
          if [ ! -z ${volthaImg} ];
          then
            IFS=: read -r volthaRepo volthaTag <<< ${volthaImg}
            EXTRA_HELM_FLAGS+=",images.voltha.repository=${volthaRepo},images.voltha.tag=${volthaTag}"
          fi
          ./voltha up
          '''
      }
    }
    stage('MIB-template') {
      steps {
        sh '''
          if [ ${withMibTemplate} = true ] ; then
            wget https://raw.githubusercontent.com/opencord/voltha-openonu-adapter/master/templates/BBSM-12345123451234512345-00000000000001-v1.json
            cat BBSM-12345123451234512345-00000000000001-v1.json | kubectl exec -it -n voltha $(kubectl get pods -n voltha | grep etcd-cluster | awk 'NR==1{print $1}') etcdctl put service/voltha/omci_mibs/templates/BBSM/12345123451234512345/00000000000001
          fi
        '''
      }
    }
    stage('disable-ONOS-apps') {
      steps {
        sh '''
          #Check withOnosApps and disable apps accordingly
          if [ ${withOnosApps} = false ] ; then
            sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost app deativate org.opencord.olt
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
          kubectl exec -n voltha $(kubectl get pods -n voltha | grep bbsim | awk 'NR==1{print $1}') bbsimctl log warn false
          #Setting link discovery
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost cfg set org.onosproject.provider.lldp.impl.LldpLinkProvider enabled ${setLinkDiscovery}
          #Setting the flow stats collection interval
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost cfg set org.onosproject.provider.of.flow.impl.OpenFlowRuleProvider flowPollFrequency ${flowStatInterval}
          #Setting the ports stats collection interval
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost cfg set org.onosproject.provider.of.device.impl.OpenFlowDeviceProvider portStatsPollFrequency ${portsStatInterval}
          # extending voltctl timeout
          sed -i 's/timeout: 10s/timeout: 5m/g' /home/cord/.volt/config-minimal
        '''
      }
    }
    stage('cpu-usage') {
      steps {
        sh '''
          psrecord $(ps aux | grep -v "grep" | grep rw_core | awk 'NR==1{print $2}') --log rwcore-activity.txt --interval 1 &
        '''
      }
    }
    stage('execute') {
      options {
        timeout(time:10)
      }
      stages {
        stage('ONUs-enabled') {
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
              echo $SECONDS > activation-time.txt
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
              echo $SECONDS > port-recognition.txt
              echo "Duration(s)" > total-time.txt
              echo "Duration(s)" > onu-activation.txt
              cat activation-time.txt >> onu-activation.txt
              paste activation-time.txt port-recognition.txt | awk '{print ($1 + $2)}' >> total-time.txt
            '''
          }
        }
      }
    }
  }
  post {
    always {
      plot([
        csvFileName: 'plot-onu-activation.csv',
        csvSeries: [[displayTableFlag: false, exclusionValues: '', file: 'onu-activation.txt', inclusionFlag: 'OFF', url: '']],
        group: 'Voltha-Scale-Numbers', numBuilds: '100', style: 'line', title: 'ONU Activation Time (200ms Delay)', useDescr: true, yaxis: 'Time (s)'
      ])

      plot([
        csvFileName: 'plot-total-time.csv',
        csvSeries: [[displayTableFlag: false, exclusionValues: '', file: 'total-time.txt', inclusionFlag: 'OFF', url: '']],
        group: 'Voltha-Scale-Numbers', numBuilds: '100', style: 'line', title: 'Port Recognition Time (200ms Delay)', useDescr: true, yaxis: 'Time (s)'
      ])
      archiveArtifacts artifacts: '*.log,*.txt'
      }
    success {
      sh '''
        #!/usr/bin/env bash
        set +e
        rm onu-activation.txt
        rm total-time.txt
        rm port-recognition.txt
        rm activation-time.txt
        cp kind-voltha/install-minimal.log $WORKSPACE/
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
      '''
    }
    cleanup {
      sh '''
        set -euo pipefail
        cd $WORKSPACE/kind-voltha
        DEPLOY_K8S=n WAIT_ON_DOWN=y ./voltha down
        cd $WORKSPACE/
        rm -rf kind-voltha/ || true
      '''
    }
  }
}
