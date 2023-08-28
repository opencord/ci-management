// Copyright 2021-2023 Open Networking Foundation (ONF) and the ONF Contributors
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

// -----------------------------------------------------------------------
// Intent:
// -----------------------------------------------------------------------
String branchName() {
    String name = 'voltha-2.11'

    // [TODO] Sanity check the target branch
    // if (name != jenkins.branch) { fatal }
    return(name)
}

// -----------------------------------------------------------------------
// Intent: Difficult at times to determine when pipeline jobs have
//   regenerated.  Hardcode a version string that can be assigned
//   per-script to be sure latest repository changes are being used.
// -----------------------------------------------------------------------
String pipelineVer() {
    String version = '5addce3fac89095d103ac5c6eedff2bb02e9ec63'
    return(version)
}

// -----------------------------------------------------------------------
// Intent: Due to lack of a reliable stack trace, construct a literal.
//         Jenkins will re-write the call stack for serialization.S
// -----------------------------------------------------------------------
// Note: Hardcoded version string used to visualize changes in jenkins UI
// -----------------------------------------------------------------------
String getIam(String func) {
    String branchName = branchName()
    String version    = pipelineVer()
    String src = [
        'ci-management',
        'jjb',
        'pipeline',
        'voltha',
        branchName,
        'bbsim-tests.groovy'
    ].join('/')

    String name = [src, version, func].join('::')
    return(name)
}

def execute_test(testTarget, workflow, testLogging, teardown, testSpecificHelmFlags = "")
{
    def infraNamespace = "default"
    def volthaNamespace = "voltha"
    def logsDir = "$WORKSPACE/${testTarget}"

    stage('IAM')
    {
	script
	{
	    String iam = [
		'ci-management',
		'jjb',
		'pipeline',
		'voltha',
		'voltha-2.11',              // release-delta
		'bbsim-tests.groovy'
	    ].join('/')
            println("** ${iam}: ENTER")

	    String cmd = "which pkill"
	    def stream = sh(
		returnStatus:false,
		returnStdout: true,
		script: cmd)
	    println(" ** ${cmd}:\n${stream}")

            println("** ${iam}: LEAVE")
	}
    }

    stage('Cleanup') {
	if (teardown) {
	    timeout(15) {
		script {
		    helmTeardown(["default", infraNamespace, volthaNamespace])
		}
	    timeout(1) {
		    sh returnStdout: false, script: '''
          # remove orphaned port-forward from different namespaces
          ps aux | grep port-forw | grep -v grep | awk '{print $2}' | xargs --no-run-if-empty kill -9 || true
          '''
		}
	    }
	}
    }

    stage ('Initialize')
    {
        steps
        {
            script
            {
                String iam = getIam('Initialize')
                println("${iam}: ENTER")

	            // VOL-4926 - Is voltha-system-tests available ?
	            String cmd = [
	                'make',
	                '-C', "$WORKSPACE/voltha-system-tests",
	                "KAIL_PATH=\"$WORKSPACE/bin\"",
	                'kail',
	            ].join(' ')
	            println(" ** Running: ${cmd}:\n")
                sh("${cmd}")

                println("${iam}: LEAVE")
            } // script
        } // steps
    } // stage

    stage('Deploy common infrastructure') {
	sh '''
    helm repo add onf https://charts.opencord.org
    helm repo update
    if [ ${withMonitoring} = true ] ; then
      helm install nem-monitoring onf/nem-monitoring \
      --set prometheus.alertmanager.enabled=false,prometheus.pushgateway.enabled=false \
      --set kpi_exporter.enabled=false,dashboards.xos=false,dashboards.onos=false,dashboards.aaa=false,dashboards.voltha=false
    fi
    '''
    }

    stage('Deploy Voltha') {
    if (teardown) {
      timeout(10) {
        script {

          sh """
          mkdir -p ${logsDir}
          _TAG=kail-startup kail -n ${infraNamespace} -n ${volthaNamespace} > ${logsDir}/onos-voltha-startup-combined.log &
          """

          // if we're downloading a voltha-helm-charts patch, then install from a local copy of the charts
          def localCharts = false
	  if (volthaHelmChartsChange != ""
	      || gerritProject == "voltha-helm-charts"
	      || branch != 'master'
	  ) {
            localCharts = true
          }

          // NOTE temporary workaround expose ONOS node ports
          def localHelmFlags = extraHelmFlags.trim() + " --set global.log_level=${logLevel.toUpperCase()} " +
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
            withMacLearning: enableMacLearning.toBoolean(),
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
          cd ${logsDir}
          gzip -k onos-voltha-startup-combined.log
          rm onos-voltha-startup-combined.log
        """
      }
      sh """
      JENKINS_NODE_COOKIE="dontKillMe" _TAG="voltha-voltha-api" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${volthaNamespace} svc/voltha-voltha-api 55555:55555; done"&
      JENKINS_NODE_COOKIE="dontKillMe" _TAG="voltha-infra-etcd" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-etcd 2379:2379; done"&
      JENKINS_NODE_COOKIE="dontKillMe" _TAG="voltha-infra-kafka" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-kafka 9092:9092; done"&
      bbsimDmiPortFwd=50075
      for i in {0..${olts.toInteger() - 1}}; do
        JENKINS_NODE_COOKIE="dontKillMe" _TAG="bbsim\${i}" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${volthaNamespace} svc/bbsim\${i} \${bbsimDmiPortFwd}:50075; done"&
        ((bbsimDmiPortFwd++))
      done
      if [ ${withMonitoring} = true ] ; then
        JENKINS_NODE_COOKIE="dontKillMe" _TAG="nem-monitoring-prometheus-server" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n default svc/nem-monitoring-prometheus-server 31301:80; done"&
      fi
      ps aux | grep port-forward
      """
      // setting ONOS log level
      script {
        setOnosLogLevels([
          onosNamespace: infraNamespace,
          apps: [
            'org.opencord.dhcpl2relay',
            'org.opencord.olt',
            'org.opencord.aaa',
            'org.opencord.maclearner',
            'org.onosproject.net.flowobjective.impl.FlowObjectiveManager',
            'org.onosproject.net.flowobjective.impl.InOrderFlowObjectiveManager'
          ],
          logLevel: logLevel
        ])
      }
    }
  }

  stage('Run test ' + testTarget + ' on ' + workflow + ' workFlow') {
    sh """
    if [ ${withMonitoring} = true ] ; then
      mkdir -p "$WORKSPACE/voltha-pods-mem-consumption-${workflow}"
      cd "$WORKSPACE/voltha-system-tests"
      make vst_venv
      source ./vst_venv/bin/activate || true
      # Collect initial memory consumption
      python scripts/mem_consumption.py -o $WORKSPACE/voltha-pods-mem-consumption-${workflow} -a 0.0.0.0:31301 -n ${volthaNamespace} || true
    fi
    """
    sh """
    mkdir -p ${logsDir}
    export ROBOT_MISC_ARGS="-d ${logsDir} ${params.extraRobotArgs} "
    ROBOT_MISC_ARGS+="-v ONOS_SSH_PORT:30115 -v ONOS_REST_PORT:30120 -v NAMESPACE:${volthaNamespace} -v INFRA_NAMESPACE:${infraNamespace} -v container_log_dir:${logsDir} -v logging:${testLogging}"
    export KVSTOREPREFIX=voltha/voltha_voltha

    make -C "$WORKSPACE/voltha-system-tests" ${testTarget} || true
    """
    getPodsInfo("${logsDir}")
    sh """
      set +e
      # collect logs collected in the Robot Framework StartLogging keyword
      cd ${logsDir}
      gzip *-combined.log || true
      rm *-combined.log || true
    """
    sh """
    if [ ${withMonitoring} = true ] ; then
      cd "$WORKSPACE/voltha-system-tests"
      source ./vst_venv/bin/activate || true
      # Collect memory consumption of voltha pods once all the tests are complete
      python scripts/mem_consumption.py -o $WORKSPACE/voltha-pods-mem-consumption-${workflow} -a 0.0.0.0:31301 -n ${volthaNamespace} || true
    fi
    """
  }
}

def collectArtifacts(exitStatus) {
  getPodsInfo("$WORKSPACE/${exitStatus}")
  sh """
  kubectl logs -n voltha -l app.kubernetes.io/part-of=voltha > $WORKSPACE/${exitStatus}/voltha.log || true
  """
  archiveArtifacts artifacts: '**/*.log,**/*.gz,**/*.txt,**/*.html,**/voltha-pods-mem-consumption-att/*,**/voltha-pods-mem-consumption-dt/*,**/voltha-pods-mem-consumption-tt/*'
  sh '''
    sync
    pkill kail || true
    which voltctl
    md5sum $(which voltctl)
  '''
  step([$class: 'RobotPublisher',
    disableArchiveOutput: false,
    logFileName: "**/*/log*.html",
    otherFiles: '',
    outputFileName: "**/*/output*.xml",
    outputPath: '.',
    passThreshold: 100,
    reportFileName: "**/*/report*.html",
    unstableThreshold: 0,
    onlyCritical: true]);
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
    DIAGS_PROFILE="VOLTHA_PROFILE"
    SSHPASS="karaf"
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
    stage('Replace voltctl') {
      // if the project is voltctl override the downloaded one with the built one
      when {
        expression {
          return gerritProject == "voltctl"
        }
      }
      steps{
        sh """
        # [TODO] - why is this platform specific (?)
        # [TODO] - revisit, command alteration has masked an error (see: voltha-2.11).
        #          find will fail when no filsystem matches are found.
        #          mv(ls) succeded simply by accident/invoked at a different time.
        # find "$WORKSPACE/voltctl/release" -name 'voltctl-*-linux-amd*' \
        #     -exec mv {} $WORKSPACE/bin/voltctl ;
        mv `ls $WORKSPACE/voltctl/release/voltctl-*-linux-amd*` $WORKSPACE/bin/voltctl
        chmod +x $WORKSPACE/bin/voltctl
        """
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
              def logging = test["logging"].toBoolean()
              def testLogging = 'False'
              if (logging) {
                  testLogging = 'True'
              }
              println "Executing test ${target} on workflow ${workflow} with logging ${testLogging} and extra flags ${flags}"
              execute_test(target, workflow, testLogging, teardown, flags)
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
