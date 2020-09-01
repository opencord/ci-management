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

topologies = [
  ['onu': 2, 'pon': 1, 'olt': 1],
  ['onu': 2, 'pon': 2, 'olt': 1],
  ['onu': 2, 'pon': 4, 'olt': 1],
  ['onu': 2, 'pon': 1, 'olt': 2],
  ['onu': 2, 'pon': 2, 'olt': 2],
  ['onu': 2, 'pon': 4, 'olt': 2],
]

pipeline {

  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 120, unit: 'MINUTES')
  }
  environment {
    JENKINS_NODE_COOKIE="dontKillMe" // do not kill processes after the build is done
    KUBECONFIG="$HOME/.kube/config"
    VOLTCONFIG="$HOME/.volt/config"
    SCHEDULE_ON_CONTROL_NODES="yes"
    FANCY=0
    NAME="minimal"

    WITH_SIM_ADAPTERS="no"
    WITH_RADIUS="no"
    WITH_BBSIM="yes"
    LEGACY_BBSIM_INDEX="no"
    DEPLOY_K8S="no"
    CONFIG_SADIS="external"

    // install everything in the default namespace
    VOLTHA_NS="default"
    ADAPTER_NS="default"
    INFRA_NS="default"
    BBSIM_NS="default"

    // workflow
    WITH_EAPOL="no"
    WITH_DHCP="no"
    WITH_IGMP="no"

    // infrastructure size
    NUM_OF_OPENONU=1
    NUM_OF_ONOS="1"
    NUM_OF_ATOMIX="1"
    NUM_OF_KAFKA="1"
    NUM_OF_ETCD="1"
  }

  stages {
    stage ('Cleanup') {
      steps {
        timeout(time: 10, unit: 'MINUTES') {
          sh returnStdout: false, script: """
            helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com
            helm repo add stable https://kubernetes-charts.storage.googleapis.com
            helm repo add onf https://charts.opencord.org
            helm repo add cord https://charts.opencord.org
            helm repo add onos https://charts.onosproject.org
            helm repo add atomix https://charts.atomix.io
            helm repo add bbsim-sadis https://ciena.github.io/bbsim-sadis-server/charts
            helm repo update

            for hchart in \$(helm list -q | grep -E -v 'docker-registry|kafkacat');
            do
                echo "Purging chart: \${hchart}"
                helm delete "\${hchart}"
            done
            bash /home/cord/voltha-scale/wait_for_pods.sh

            cd $WORKSPACE
            rm -rf $WORKSPACE/*
          """
        }
      }
    }
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
        script {
          sh(script:"""
          if [ '${kindVolthaChange}' != '' ] ; then
          cd $WORKSPACE/kind-voltha;
          git fetch https://gerrit.opencord.org/kind-voltha ${volthaSystemTestsChange} && git checkout FETCH_HEAD
          fi
          """)
        }
      }
    }
    stage('Clone voltha-system-tests') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "https://gerrit.opencord.org/voltha-system-tests", ]],
          branches: [[ name: "${release}", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-system-tests"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        script {
          sh(script:"""
            if [ '${volthaSystemTestsChange}' != '' ] ; then
              cd $WORKSPACE/voltha-system-tests;
              git fetch https://gerrit.opencord.org/voltha-system-tests ${volthaSystemTestsChange} && git checkout FETCH_HEAD
            fi
            """)
        }
      }
    }
    stage('Deploy monitoring') {
      steps {
        sh '''
        helm install nem-monitoring cord/nem-monitoring \
          -f $HOME/voltha-scale/grafana.yaml \
          --set prometheus.alertmanager.enabled=false,prometheus.pushgateway.enabled=false \
          --set kpi_exporter.enabled=false,dashboards.xos=false,dashboards.onos=false,dashboards.aaa=false,dashboards.voltha=false
        '''
      }
    }
    stage('Deploy and test') {
      steps {
          repeat_deploy_and_test(topologies)
      }
    }
  }
  post {
    always {
      archiveArtifacts artifacts: '*-install-minimal.log,*-minimal-env.sh,logs/**/*,plots/*'
    }
  }
}

def repeat_deploy_and_test(list) {
  for (int i = 0; i < list.size(); i++) {
    stage('Deploy topology: ' + list[i]['olt'] + "-" + list[i]['pon'] + "-" + list[i]['onu']) {
      timeout(time: 10, unit: 'MINUTES') {
        sh returnStdout: false, script: """


        for hchart in \$(helm list -q | grep -E -v 'bbsim-sadis-server|kafka|onos|radius|nem-monitoring');
        do
            echo "Purging chart: \${hchart}"
            helm delete "\${hchart}"
        done
        bash /home/cord/voltha-scale/wait_for_pods.sh
        """
        sh returnStdout: false, script: """
        cd $WORKSPACE/kind-voltha/
        source $WORKSPACE/kind-voltha/releases/${release}

        NUM_OF_BBSIM=${list[i]['olt']}
        EXTRA_HELM_FLAGS+="--set enablePerf=true,pon=${list[i]['pon']},onu=${list[i]['onu']} "
        ./voltha up

        cp minimal-env.sh ../${list[i]['olt']}-${list[i]['pon']}-${list[i]['onu']}-minimal-env.sh
        cp install-minimal.log ../${list[i]['olt']}-${list[i]['pon']}-${list[i]['onu']}-install-minimal.log

        # load MIB Template
        rm -f BBSM-12345123451234512345-00000000000001-v1.json
        wget https://raw.githubusercontent.com/opencord/voltha-openonu-adapter/master/templates/BBSM-12345123451234512345-00000000000001-v1.json
        cat BBSM-12345123451234512345-00000000000001-v1.json | kubectl exec -it \$(kubectl get pods -l app=etcd | awk 'NR==2{print \$1}') etcdctl put service/voltha/omci_mibs/templates/BBSM/12345123451234512345/00000000000001
        """
        //sleep(120) // TODO can we improve and check once the bbsim-sadis-server is actually done loading subscribers??
        // TODO do we need to remove the device from ONOS?
      }
    }
    stage('Test topology: ' + list[i]['olt'] + "-" + list[i]['pon'] + "-" + list[i]['onu']) {
      timeout(time: 10, unit: 'MINUTES') {
        sh returnStdout: false, script: """

        LOG_FOLDER=$WORKSPACE/logs/${list[i]['olt']}-${list[i]['pon']}-${list[i]['onu']}

        mkdir -p \$LOG_FOLDER/RobotLogs/
        cd $WORKSPACE/voltha-system-tests
        make vst_venv

        # track the start date of the test
        start=\$(date +%s)

        export ROBOT_PARAMS="-v olt:1 \
          -v pon:${list[i]['pon']} \
          -v onu:${list[i]['onu']} \
          -v workflow:dt \
          -v withEapol:false \
          -v withDhcp:false \
          -v withIgmp:false \
          --noncritical non-critical \
          -e teardown \
          -e authentication \
          -e dhcp"

        cd $WORKSPACE/voltha-system-tests
        source ./vst_venv/bin/activate
        robot -d \$LOG_FOLDER/RobotLogs/ \
          \$ROBOT_PARAMS tests/scale/Voltha_Scale_Tests.robot

        # track the end date of the test
        end=\$(date +%s)

        # calculate the test execution time (in minutes)
        execution_time=\$(( (end - start) / 60))
        if [[ \$execution_time -eq 0 ]]; then
          execution_time=1
        fi

        mkdir -p \$LOG_FOLDER/plots
        python tests/scale/sizing.py -o \$LOG_FOLDER/plots -s \$execution_time
        """
      }
    }
  }
}
