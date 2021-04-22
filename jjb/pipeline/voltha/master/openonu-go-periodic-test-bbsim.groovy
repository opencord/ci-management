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

def execute_test(testName, workflow, testTarget, outputDir, testSpecificHelmFlags = "") {
  def infraNamespace = "default"
  def volthaNamespace = "voltha"
  def robotLogsDir = "RobotLogs"
  stage('Cleanup') {
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
    }
  }
  stage('Deploy Voltha') {
    timeout(20) {
      script {

        // if we're downloading a voltha-helm-charts patch, then install from a local copy of the charts
        def localCharts = false
        if (volthaHelmChartsChange != "") {
          localCharts = true
        }

        // NOTE temporary workaround expose ONOS node ports
        extraHelmFlags = extraHelmFlags + " --set onos-classic.onosSshPort=30115 " +
        " --set onos-classic.onosApiPort=30120 " +
        " --set onos-classic.onosOfPort=31653 " +
        " --set onos-classic.individualOpenFlowNodePorts=true " + testSpecificHelmFlags
        volthaDeploy([
          infraNamespace: infraNamespace,
          volthaNamespace: volthaNamespace,
          workflow: workflow.toLowerCase(),
          extraHelmFlags: extraHelmFlags,
          localCharts: localCharts,
          bbsimReplica: olts,
          dockerRegistry: "mirror.registry.opennetworking.org"
          ])
      }
      // start logging
      sh """
      mkdir -p ${outputDir}
      _TAG=kail-${workflow} kail -n infra -n voltha > ${outputDir}/onos-voltha-combined.log &
      """
      sh """
      JENKINS_NODE_COOKIE="dontKillMe" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${volthaNamespace} svc/voltha-voltha-api 55555:55555; done"&
      JENKINS_NODE_COOKIE="dontKillMe" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-etcd 2379:2379; done"&
      JENKINS_NODE_COOKIE="dontKillMe" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-kafka 9092:9092; done"&
      ps aux | grep port-forward
      """
      getPodsInfo("${outputDir}")
    }
  }
  stage('Run test ' + testTarget + ' on ' + workflow + ' workFlow') {
    sh """
    mkdir -p $WORKSPACE/${robotLogsDir}/${testName}
    export ROBOT_MISC_ARGS="-d $WORKSPACE/${robotLogsDir}/${testName} "
    ROBOT_MISC_ARGS+="-v ONOS_SSH_PORT:30115 -v ONOS_REST_PORT:30120"
    export KVSTOREPREFIX=voltha/voltha_voltha

    make -C $WORKSPACE/voltha-system-tests ${testTarget} || true
    """
  }
}

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: 130, unit: 'MINUTES')
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

    stage('Run E2E Tests 1t1gem') {
      steps {
        execute_test("1t1gem", "att", makeTarget, "$WORKSPACE/1t1gem")
       }
     }

    stage('Run E2E Tests 1t4gem') {
      steps {
        execute_test("1t4gem", "att", make1t4gemTestTarget, "$WORKSPACE/1t4gem")
       }
     }

    stage('Run E2E Tests 1t8gem') {
      steps {
        execute_test("1t8gem", "att", make1t8gemTestTarget, "$WORKSPACE/1t8gem")
      }
    }

    stage('Run MIB Upload Tests') {
      when { beforeAgent true; expression { return "${olts}" == "1" } }
      steps {
        script {
          def mibUploadHelmFlags = "--set log_agent.enabled=False "
          mibUploadHelmFlags += " --set pon=2,onu=2,controlledActivation=only-onu "
          execute_test("1t8gem", "att", "mib-upload-templating-openonu-go-adapter-test", "$WORKSPACE/1t8gem", mibUploadHelmFlags)
        }
      }
    }

    stage('Reconcile DT workflow') {
      steps {
        script {
          def reconcileHelmFlags = "--set log_agent.enabled=False "
          execute_test("ReconcileDT", "dt", makeReconcileDtTestTarget, "$WORKSPACE/ReconcileDT", reconcileHelmFlags)
        }
      }
    }

    stage('Reconcile ATT workflow') {
      steps {
        script {
          def reconcileHelmFlags = "--set log_agent.enabled=False "
          execute_test("ReconcileATT", "att", makeReconcileTestTarget, "$WORKSPACE/ReconcileATT", reconcileHelmFlags)
        }
      }
    }

    stage('Reconcile TT workflow') {
      steps {
        script {
          def reconcileHelmFlags = "--set log_agent.enabled=False "
          execute_test("ReconcileTT", "tt", makeReconcileTestTarget, "$WORKSPACE/ReconcileTT", reconcileHelmFlags)
        }
      }
    }
  }
  post {
    aborted {
      getPodsInfo("$WORKSPACE/failed")
      sh """
      kubectl logs -n voltha -l app.kubernetes.io/part-of=voltha > $WORKSPACE/failed/voltha.log
      """
      archiveArtifacts artifacts: '**/*.log,**/*.txt,**/*.html'
    }
    failure {
      getPodsInfo("$WORKSPACE/failed")
      sh """
      kubectl logs -n voltha -l app.kubernetes.io/part-of=voltha > $WORKSPACE/failed/voltha.log
      """
      archiveArtifacts artifacts: '**/*.log,**/*.txt,**/*.html'
    }
    always {
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: "RobotLogs/*/log*.html",
        otherFiles: '',
        outputFileName: "RobotLogs/*/output*.xml",
        outputPath: '.',
        passThreshold: 100,
        reportFileName: "RobotLogs/*/report*.html",
        unstableThreshold: 0]);
      archiveArtifacts artifacts: '**/*.log,**/*.gz,**/*.txt,**/*.html'
      sh '''
        sync
        pkill kail || true
        which voltctl
        md5sum $(which voltctl)
      '''
    }
  }
}
