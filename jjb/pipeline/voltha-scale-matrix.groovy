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
    VOLTHA_LOG_LEVEL="WARN"

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
    NUM_OF_OPENONU="${openonuAdapterReplicas}"
    NUM_OF_ONOS="${onosReplicas}"
    NUM_OF_ATOMIX="${atomixReplicas}"
    NUM_OF_KAFKA="${kafkaReplicas}"
    NUM_OF_ETCD="${etcdReplicas}"
  }

  stages {
    stage ('Parse parameters') {
      steps {
        script {
          format = "format is 'olt-pon-onu' separated bya comma, eg: '1-16-16, 1-16-32, 2-16-32'"
          source = params.topologies

          if (source == null || source == "") {
            throw new Exception("You need to specify some deployment topologies, " + format)
          }

          topologies = []

          for(topo in source.split(",")) {
            t = topo.split("-")
            topologies.add(['olt': t[0].trim(), 'pon': t[1].trim(), 'onu': t[2].trim()])
          }

          if (topologies.size() == 0) {
            throw new Exception("Not enough topologies defined, " + format)
          }
          println "Deploying topologies:"
          println topologies
        }
      }
    }
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
    stage('Deploy and test') {
      steps {
          repeat_deploy_and_test(topologies)
      }
    }
    stage('Aggregate stats') {
      steps {
        sh returnStdout: false, script: """
        export IN_FOLDER=$WORKSPACE/stats/
        export OUT_FOLDER=$WORKSPACE/plots/
        mkdir -p \$OUT_FOLDER
        cd $WORKSPACE/voltha-system-tests
        make vst_venv
        source ./vst_venv/bin/activate

        sleep 60 # we have to wait for prometheus to collect all the information

        python tests/scale/stats-aggregation.py -s \$IN_FOLDER -o \$OUT_FOLDER
        """
      }
    }
  }
  post {
    always {
      archiveArtifacts artifacts: '*-install-minimal.log,*-minimal-env.sh,RobotLogs/**/*,stats/**/*,logs/**/*'
    }
  }
}

def repeat_deploy_and_test(list) {
  for (int i = 0; i < list.size(); i++) {
    stage('Cleanup') {
      sh returnStdout: false, script: """
      for hchart in \$(helm list -q | grep -E -v 'bbsim-sadis-server|onos|radius');
      do
          echo "Purging chart: \${hchart}"
          helm delete "\${hchart}"
      done
      bash /home/cord/voltha-scale/wait_for_pods.sh
      """
    }
    stage('Deploy monitoring infrastructure') {
      sh returnStdout: false, script: '''
      helm install nem-monitoring cord/nem-monitoring \
      -f $HOME/voltha-scale/grafana.yaml \
      --set prometheus.alertmanager.enabled=false,prometheus.pushgateway.enabled=false \
      --set kpi_exporter.enabled=false,dashboards.xos=false,dashboards.onos=false,dashboards.aaa=false,dashboards.voltha=false

      # TODO download this file from https://github.com/opencord/helm-charts/blob/master/scripts/wait_for_pods.sh
      bash /home/cord/voltha-scale/wait_for_pods.sh
      '''
    }
    stage('Deploy topology: ' + list[i]['olt'] + "-" + list[i]['pon'] + "-" + list[i]['onu']) {
      timeout(time: 10, unit: 'MINUTES') {
        script {
          now = new Date();
          currentRunStart = now.getTime() / 1000;
          println("Start: " + currentRunStart)
        }
        sh returnStdout: false, script: """
        cd $WORKSPACE/kind-voltha/

        if [ '${release.trim()}' != 'master' ]; then
          source $WORKSPACE/kind-voltha/releases/${release}
        fi

        # if it's newer than voltha-2.4 set the correct BBSIM_CFG
        if [ '${release.trim()}' != 'voltha-2.4' ]; then
          export BBSIM_CFG="$WORKSPACE/kind-voltha/configs/bbsim-sadis-dt.yaml"
        fi

        export NUM_OF_BBSIM=${list[i]['olt']}
        export EXTRA_HELM_FLAGS+="--set enablePerf=true,pon=${list[i]['pon']},onu=${list[i]['onu']} "
        export EXTRA_HELM_FLAGS+="--set prometheus.kafka.enabled=true,prometheus.operator.enabled=true,prometheus.jmx.enabled=true,prometheus.operator.serviceMonitor.namespace=default"
        ./voltha up

        # disable LLDP
        sshpass -e ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@127.0.0.1 cfg set org.onosproject.provider.lldp.impl.LldpLinkProvider enabled false

        cp minimal-env.sh ../${list[i]['olt']}-${list[i]['pon']}-${list[i]['onu']}-minimal-env.sh
        cp install-minimal.log ../${list[i]['olt']}-${list[i]['pon']}-${list[i]['onu']}-install-minimal.log
        """
        sleep(120) // TODO can we improve and check once the bbsim-sadis-server is actually done loading subscribers??
      }
    }
    stage('Test topology: ' + list[i]['olt'] + "-" + list[i]['pon'] + "-" + list[i]['onu']) {
      timeout(time: 15, unit: 'MINUTES') {
        sh returnStdout: false, script: """
        mkdir -p $WORKSPACE/RobotLogs/${list[i]['olt']}-${list[i]['pon']}-${list[i]['onu']}
        cd $WORKSPACE/voltha-system-tests
        make vst_venv

        export ROBOT_PARAMS=" \
          -v olt:${list[i]['olt']} \
          -v pon:${list[i]['pon']} \
          -v onu:${list[i]['onu']} \
          -v workflow:dt \
          -v withEapol:false \
          -v withDhcp:false \
          -v withIgmp:false \
          -e authentication \
          -e dhcp"

        cd $WORKSPACE/voltha-system-tests
        source ./vst_venv/bin/activate
        robot -d $WORKSPACE/RobotLogs/${list[i]['olt']}-${list[i]['pon']}-${list[i]['onu']} \
        \$ROBOT_PARAMS tests/scale/Voltha_Scale_Tests.robot
        """
      }
    }
    stage('Collect metrics: ' + list[i]['olt'] + "-" + list[i]['pon'] + "-" + list[i]['onu']) {
      script {
        now = new Date();
        currentRunEnd = now.getTime() / 1000;
        println("End: " + currentRunEnd)
        delta = currentRunEnd - currentRunStart
        println("Delta: " + delta)
        minutesDelta = Math.ceil(delta / 60).toInteger()
        println("Delta in minutes: " + minutesDelta)
      }
      sh returnStdout: false, script: """
      export LOG_FOLDER=$WORKSPACE/stats/${list[i]['olt']}-${list[i]['pon']}-${list[i]['onu']}
      mkdir -p \$LOG_FOLDER
      cd $WORKSPACE/voltha-system-tests
      make vst_venv
      source ./vst_venv/bin/activate

      sleep 60 # we have to wait for prometheus to collect all the information

      python tests/scale/sizing.py -o \$LOG_FOLDER -s ${minutesDelta}
      """
    }
  }
}
