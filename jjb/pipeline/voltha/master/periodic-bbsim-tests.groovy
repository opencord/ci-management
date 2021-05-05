// Copyright 2021-present Open Networking Foundation
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

// voltha-2.x e2e tests for openonu-go
// uses bbsim to simulate OLT/ONUs

library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://gerrit.opencord.org/ci-management.git'
])

def clusterName = "kind-ci"

def execute_test(testTarget, workflow, teardown, testSpecificHelmFlags = "") {
  def infraNamespace = "default"
  def volthaNamespace = "voltha"
  def robotLogsDir = "RobotLogs"
  stage('Cleanup') {
    if (teardown) {
      timeout(15) {
        script {
          helmTeardown(["default", infraNamespace, volthaNamespace])
        }
        timeout(1) {
          sh returnStdout: false, script: '''
          # remove orphaned port-forward from different namespaces
          ps aux | grep port-forw | grep -v grep | awk '{print $2}' | xargs --no-run-if-empty kill -9
          '''
        }
      }
    }
  }
  stage('Deploy Voltha') {
    if (teardown) {
      timeout(20) {
        script {

          sh """
          mkdir -p $WORKSPACE/${testTarget}-components
          _TAG=kail-startup kail -n infra -n voltha > $WORKSPACE/${testTarget}-components/onos-voltha-startup-combined.log &
          """

          // if we're downloading a voltha-helm-charts patch, then install from a local copy of the charts
          def localCharts = false
          if (volthaHelmChartsChange != "") {
            localCharts = true
          }

          // NOTE temporary workaround expose ONOS node ports
          def localHelmFlags = extraHelmFlags + " --set global.log_level=${logLevel.toUpperCase()} " +
          " --set onos-classic.onosSshPort=30115 " +
          " --set onos-classic.onosApiPort=30120 " +
          " --set onos-classic.onosOfPort=31653 " +
          " --set onos-classic.individualOpenFlowNodePorts=true " + testSpecificHelmFlags

          if (gerritProject != "") {
            localHelmFlags = "${localHelmFlags} " + getVolthaImageFlags("${gerritProject}")
          }

          volthaDeploy([
            infraNamespace: infraNamespace,
            volthaNamespace: volthaNamespace,
            workflow: workflow.toLowerCase(),
            extraHelmFlags: localHelmFlags,
            localCharts: localCharts,
            bbsimReplica: olts.toInteger(),
            dockerRegistry: registry,
            ])
        }

        // stop logging
        sh """
          P_IDS="\$(ps e -ww -A | grep "_TAG=kail-startup" | grep -v grep | awk '{print \$1}')"
          if [ -n "\$P_IDS" ]; then
            echo \$P_IDS
            for P_ID in \$P_IDS; do
              kill -9 \$P_ID
            done
          fi
        """
      }
      sh """
      JENKINS_NODE_COOKIE="dontKillMe" _TAG="voltha-voltha-api" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${volthaNamespace} svc/voltha-voltha-api 55555:55555; done"&
      JENKINS_NODE_COOKIE="dontKillMe" _TAG="voltha-infra-etcd" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-etcd 2379:2379; done"&
      JENKINS_NODE_COOKIE="dontKillMe" _TAG="voltha-infra-kafka" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-kafka 9092:9092; done"&
      bbsimDmiPortFwd=50075
      for i in {0..${olts.toInteger() - 1}}; do
        JENKINS_NODE_COOKIE="dontKillMe" _TAG="bbsim\${i}" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${volthaNamespace} svc/bbsim\${i} 50075:\${bbsimDmiPortFwd}; done"&
        ((bbsimDmiPortFwd++))
      done
      ps aux | grep port-forward
      """
    }
  }
  stage('Run test ' + testTarget + ' on ' + workflow + ' workFlow') {
    // start logging
    sh """
    mkdir -p $WORKSPACE/${testTarget}-components
    _TAG=kail-${workflow} kail -n infra -n voltha > $WORKSPACE/${testTarget}-components/onos-voltha-combined.log &
    """
    sh """
    mkdir -p $WORKSPACE/${robotLogsDir}/${testTarget}-robot
    export ROBOT_MISC_ARGS="-d $WORKSPACE/${robotLogsDir}/${testTarget}-robot "
    ROBOT_MISC_ARGS+="-v ONOS_SSH_PORT:30115 -v ONOS_REST_PORT:30120 -v INFRA_NAMESPACE:${infraNamespace}"
    export KVSTOREPREFIX=voltha/voltha_voltha

    make -C $WORKSPACE/voltha-system-tests ${testTarget} || true
    """
    // stop logging
    sh """
      P_IDS="\$(ps e -ww -A | grep "_TAG=kail-${workflow}" | grep -v grep | awk '{print \$1}')"
      if [ -n "\$P_IDS" ]; then
        echo \$P_IDS
        for P_ID in \$P_IDS; do
          kill -9 \$P_ID
        done
      fi
    """
    getPodsInfo("$WORKSPACE/${testTarget}-components")
  }
}

def collectArtifacts(exitStatus) {
  getPodsInfo("$WORKSPACE/${exitStatus}")
  sh """
  kubectl logs -n voltha -l app.kubernetes.io/part-of=voltha > $WORKSPACE/${exitStatus}/voltha.log || true
  """
  archiveArtifacts artifacts: '**/*.log,**/*.gz,**/*.txt,**/*.html'
  sh '''
    sync
    pkill kail || true
    which voltctl
    md5sum $(which voltctl)
  '''
  step([$class: 'RobotPublisher',
    disableArchiveOutput: false,
    logFileName: "RobotLogs/*/log*.html",
    otherFiles: '',
    outputFileName: "RobotLogs/*/output*.xml",
    outputPath: '.',
    passThreshold: 100,
    reportFileName: "RobotLogs/*/report*.html",
    unstableThreshold: 0]);
}

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: "${timeout}", unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-${clusterName}"
    VOLTCONFIG="$HOME/.volt/config"
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    ROBOT_MISC_ARGS="-e PowerSwitch ${params.extraRobotArgs}"
    DIAGS_PROFILE="VOLTHA_PROFILE"
  }
  stages {
    stage('Download Code') {
      steps {
        getVolthaCode([
          branch: "${branch}",
          gerritProject: "${gerritProject}",
          gerritRefspec: "${gerritRefspec}",
          volthaSystemTestsChange: "${volthaSystemTestsChange}",
          volthaHelmChartsChange: "${volthaHelmChartsChange}",
        ])
      }
    }
    stage('Build patch') {
      // build the patch only if gerritProject is specified
      when {
        expression {
          return !gerritProject.isEmpty()
        }
      }
      steps {
        // NOTE that the correct patch has already been checked out
        // during the getVolthaCode step
        buildVolthaComponent("${gerritProject}")
      }
    }
    stage('Create K8s Cluster') {
      steps {
        script {
          def clusterExists = sh returnStdout: true, script: """
          kind get clusters | grep ${clusterName} | wc -l
          """
          if (clusterExists.trim() == "0") {
            createKubernetesCluster([nodes: 3, name: clusterName])
          }
        }
      }
    }
    stage('Load image in kind nodes') {
      when {
        expression {
          return !gerritProject.isEmpty()
        }
      }
      steps {
        loadToKind()
      }
    }
    stage('Parse and execute tests') {
        steps {
          script {
            def tests = readYaml text: testTargets

            for(int i = 0;i<tests.size();i++) {
              def test = tests[i]
              def target = test["target"]
              def workflow = test["workflow"]
              def flags = test["flags"]
              def teardown = test["teardown"].toBoolean()
              println "Executing test ${target} on workflow ${workflow} with extra flags ${flags}"
              execute_test(target, workflow, teardown, flags)
            }
          }
        }
    }
  }
  post {
    aborted {
      collectArtifacts("aborted")
    }
    failure {
      collectArtifacts("failed")
    }
    always {
      collectArtifacts("always")
    }
  }
}
