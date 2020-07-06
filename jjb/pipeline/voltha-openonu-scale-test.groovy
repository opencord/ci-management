// Copyright 2019-present Open Networking Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// deploy VOLTHA using kind-voltha and performs a scale test

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 30, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/config"
    VOLTCONFIG="$HOME/.volt/config"
    SSHPASS="karaf"
    PATH="$PATH:$WORKSPACE/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    TYPE="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="no"
    WITH_RADIUS="yes"
    WITH_BBSIM="yes"
    LEGACY_BBSIM_INDEX="no"
    DEPLOY_K8S="no"
    CONFIG_SADIS="external"

    // install everything in the default namespace
    VOLTHA_NS="default"
    ADAPTER_NS="default"
    INFRA_NS="default"
    BBSIM_NS="default"

    VOLTHA_LOG_LEVEL="${logLevel}"
    NUM_OF_BBSIM="${olts}"
    NUM_OF_OPENONU="${openonuAdapterReplicas}"
    EXTRA_HELM_FLAGS="${extraHelmFlags} "

    VOLTHA_CHART="${volthaChart}"
    VOLTHA_BBSIM_CHART="${bbsimChart}"
    VOLTHA_ADAPTER_OPEN_OLT_CHART="${openoltAdapterChart}"
    VOLTHA_ADAPTER_OPEN_ONU_CHART="${openonuAdapterChart}"
  }

  stages {
    stage('Clone kind-voltha') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "https://gerrit.opencord.org/kind-voltha", ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "kind-voltha"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
      }
    }

    stage('Deploy VOLTHA') {
      steps {
        script {
          sh returnStdout: false, script: """
            export EXTRA_HELM_FLAGS+='--set enablePerf=true,pon=${pons},onu=${onus} '

            # BBSim custom image handling
            IFS=: read -r bbsimRepo bbsimTag <<< ${bbsimImg}
            EXTRA_HELM_FLAGS+="--set images.bbsim.repository=\$bbsimRepo,images.bbsim.tag=\$bbsimTag "

            # VOLTHA and ofAgent custom image handling
            IFS=: read -r rwCoreRepo rwCoreTag <<< ${rwCoreImg}
            IFS=: read -r ofAgentRepo ofAgentTag <<< ${ofAgentImg}
            EXTRA_HELM_FLAGS+="--set images.rw_core.repository=\$rwCoreRepo,images.rw_core.tag=\$rwCoreTag,images.ofagent.repository=\$ofAgentRepo,images.ofagent.tag=\$ofAgentTag "

            # OpenOLT custom image handling
            IFS=: read -r openoltAdapterRepo openoltAdapterTag <<< ${openoltAdapterImg}
            EXTRA_HELM_FLAGS+="--set images.adapter_open_olt.repository=\$openoltAdapterRepo,images.adapter_open_olt.tag=\$openoltAdapterTag "

            # OpenONU custom image handling
            IFS=: read -r openonuAdapterRepo openonuAdapterTag <<< ${openonuAdapterImg}
            EXTRA_HELM_FLAGS+="--set images.adapter_open_onu.repository=\$openonuAdapterRepo,images.adapter_open_onu.tag=\$openonuAdapterTag "

            cd $WORKSPACE/kind-voltha/

            ./voltha up
          """
        }
        sleep(120)
      }
    }

    stage('Configuration') {
      steps {
        sh '''
          #Setting LOG level to ${logLevel}
          sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 log:set ${logLevel}
          kubectl exec $(kubectl get pods | grep -E "bbsim[0-9]" | awk 'NR==1{print $1}') -- bbsimctl log ${logLevel} false
        '''
      }
    }

    stage('ONU-Activation') {
      options {
        timeout(time:10)
      }
      steps {
        sh '''
        voltctl device create -t openolt -H bbsim:50060
        voltctl device list --filter Type~openolt -q | xargs voltctl device enable

        i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
        until [ $i -eq ${expectedOnus} ]
        do
          echo "$i ONUs ACTIVE of ${expectedOnus} expected (time: $SECONDS)"
          sleep ${pollInterval}
          i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
        done
        echo "${expectedOnus} ONUs Activated in $SECONDS seconds (time: $SECONDS)"
        echo "DURATION(s) > onu-activation-time-num.txt
        echo $SECONDS >> onu-activation-time-num.txt
        '''
      }
    }
  }
  post {
    always {
      sh '''
        echo "#-of-ONUs" > voltha-devices-count.txt
        echo $(voltctl device list | grep -v OLT | grep ACTIVE | wc -l) >> voltha-devices-count.txt
      '''
    }
    cleanup {
      sh '''
        set -euo pipefail
        cd $WORKSPACE/kind-voltha
        DEPLOY_K8S=n WAIT_ON_DOWN=y ./voltha down
        cd $WORKSPACE/
        rm -rf kind-voltha/
      '''
    }
  }