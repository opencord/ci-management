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
    PATH="$WORKSPACE/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
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

    // TODO make configurable
    VOLTHA_LOG_LEVEL="${logLevel}"
    NUM_OF_BBSIM="${olts}"
    NUM_OF_OPENONU=4
    NUM_OF_ONOS=1
    NUM_OF_ATOMIX=0

    // TODO make charts configurable (low priority)
    VOLTHA_CHART="onf/voltha"
    VOLTHA_CHART_VERSION="latest"
    VOLTHA_BBSIM_CHART="onf/bbsim"
    VOLTHA_BBSIM_CHART_VERSION="latest"
    VOLTHA_ADAPTER_SIM_CHART="onf/voltha-adapter-simulated"
    VOLTHA_ADAPTER_SIM_CHART_VERSION="latest"
    VOLTHA_ADAPTER_OPEN_OLT_CHART="onf/voltha-adapter-openolt"
    VOLTHA_ADAPTER_OPEN_OLT_CHART_VERSION="latest"
    VOLTHA_ADAPTER_OPEN_ONU_CHART="onf/voltha-adapter-openonu"
    VOLTHA_ADAPTER_OPEN_ONU_CHART_VERSION="latest"
  }

  stages {
    stage ('Cleanup') {
      // TODO remove plot files
      steps {
        sh returnStdout: false, script: """
        test -e $WORKSPACE/kind-voltha/voltha && cd $WORKSPACE/kind-voltha && ./voltha down
        cd $WORKSPACE
        rm -rf $WORKSPACE/*
        """
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
      }
    }
    stage('Clone voltha-system-tests') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "https://gerrit.opencord.org/voltha-system-tests", ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-system-tests"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        // TODO use master once the tests are merged
        script {
          sh(script:"cd voltha-system-tests; git fetch https://gerrit.opencord.org/voltha-system-tests refs/changes/79/18779/12 && git checkout FETCH_HEAD")
        }
      }
    }
    // stage('Deploy monitoring infrastructure') {
    //   steps {
    //     sh '''
    //     helm install -n nem-monitoring cord/nem-monitoring --set kpi_exporter.enabled=false,dashboards.xos=false,dashboards.onos=false,dashboards.aaa=false,dashboards.voltha=false
    //     '''
    //   }
    // }
    stage('Deploy Voltha') {
      steps {
        script {
          // TODO add support for custom images, see voltha-pyshical-build-and-test.groovy
          // TODO install kafka outside kind-voltha (can't use 3 instances otherwise)
          // TODO install etcd outside kind-voltha (no need to redeploy the operator everytime)
          sh returnStdout: false, script: """
            export EXTRA_HELM_FLAGS+='--set enablePerf=true,pon=${pons},onu=${onus}'


            cd $WORKSPACE/kind-voltha/

            ./voltha up
          """
        }
      }
    }
    stage('Push MIB template to ETCD') {
      steps {
        sh '''
          if [ ${withMibTemplate} = true ] ; then
            rm -f BBSM-12345123451234512345-00000000000001-v1.json
            wget https://raw.githubusercontent.com/opencord/voltha-openonu-adapter/master/templates/BBSM-12345123451234512345-00000000000001-v1.json
            cat BBSM-12345123451234512345-00000000000001-v1.json | kubectl exec -it $(kubectl get pods | grep etcd-cluster | awk 'NR==1{print $1}') etcdctl put service/voltha/omci_mibs/templates/BBSM/12345123451234512345/00000000000001
          fi
        '''
      }
    }
    stage('Run Test') {
      steps {
        // TODO use -i/-e in robot to customize runs for:
        // - runs without flows
        // - runs without subscriber provisioning
        sh '''
          mkdir -p $WORKSPACE/RobotLogs
          cd voltha-system-tests
          make vst_venv
          source ./vst_venv/bin/activate
          robot -d $WORKSPACE/RobotLogs \
            -v olt:${olts} \
            -v pon:${pons} \
            -v onu:${onus} \
            -v workflow:att \
            -e teardown \
            tests/scale/Voltha_Scale_Tests.robot
        '''
      }
    }
    stage('Collect results') {
      steps {
        sh '''
          cd voltha-system-tests
          source ./vst_venv/bin/activate
          python tests/scale/collect-result.py -r ../RobotLogs/output.xml -p ../plots> execution-time.txt
        '''
      }
    }
  }
  post {
    always {
      plot([
        csvFileName: 'scale-test.csv',
        csvSeries: [
          [file: 'plots/plot-voltha-onus.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-onos-ports.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-voltha-flows-before.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-onos-flows-before.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-onos-auth.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-voltha-flows-after.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-onos-flows-after.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
          [file: 'plots/plot-onos-dhcp.txt', displayTableFlag: false, exclusionValues: '', inclusionFlag: 'OFF', url: ''],
        ],
        group: 'Voltha-Scale-Numbers', numBuilds: '20', style: 'line', title: "Scale Test (OLTs: ${olts}, PONs: ${pons}, ONUs: ${onus})", yaxis: 'Time (s)', useDescr: true
      ])
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: 'RobotLogs/log*.html',
        otherFiles: '',
        outputFileName: 'RobotLogs/output*.xml',
        outputPath: '.',
        passThreshold: 100,
        reportFileName: 'RobotLogs/report*.html',
        unstableThreshold: 0]);
      archiveArtifacts artifacts: 'kind-voltha/install-minimal.log,voltha-system-tests/*.txt'
    }
  }
}
