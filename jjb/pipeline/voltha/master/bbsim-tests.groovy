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

// [TODO] Update syntax below to the latest supported
library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://gerrit.opencord.org/ci-management.git'
])

//------------------//
//---]  GLOBAL  [---//
//------------------//
String clusterName = 'kind-ci' // was def

// -----------------------------------------------------------------------
// Intent:
// -----------------------------------------------------------------------
String branchName() {
    String name = 'master'

    // [TODO] Sanity check the target branch
    // if (name != jenkins.branch) { fatal }
    return(name)
}

// -----------------------------------------------------------------------
// Intent: Due to lack of a reliable stack trace, construct a literal.
//         Jenkins will re-write the call stack for serialization.
// -----------------------------------------------------------------------
String getIam(String func) {
    String branchName = branchName()
    String src = [
        'ci-management',
        'jjb',
        'pipeline',
        'voltha',
        branchName,
        'bbsim-tests.groovy'
    ].join('/')

    String name = [src, func].join('::')
    return(name)
}

// -----------------------------------------------------------------------
// Intent: Determine if working on a release branch.
//   Note: Conditional is legacy, should also check for *-dev or *-pre
// -----------------------------------------------------------------------
Boolean isReleaseBranch(String name)
{
    // List modifiers = ['-dev', '-pre', 'voltha-x.y.z-pre']
    // if branchName in modifiers
    return(name != 'master') // OR branchName.contains('-')
}

// -----------------------------------------------------------------------
// Intent:
// -----------------------------------------------------------------------
void execute_test(testTarget, workflow, testLogging, teardown, testSpecificHelmFlags='')
{
    String infraNamespace  = 'default'
    String volthaNamespace = 'voltha'
    String logsDir = "$WORKSPACE/${testTarget}"

    stage('IAM')
    {
        script
        {
            // Announce ourselves for log usability
            String iam = getIam('execute_test') 
            println("${iam}: ENTER")
            println("${iam}: LEAVE")
        }
    }

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage('Cleanup')
    {
        if (teardown) {
            timeout(15) {
                script {
                    helmTeardown(['default', infraNamespace, volthaNamespace])
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

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage('Deploy common infrastructure')
    {
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

    stage('Deploy Voltha')
    {
        if (teardown)
        {
            timeout(10)
            {
                script
                {
          sh """
          mkdir -p ${logsDir}
          _TAG=kail-startup kail -n ${infraNamespace} -n ${volthaNamespace} > ${logsDir}/onos-voltha-startup-combined.log &
          """

	  // if we're downloading a voltha-helm-charts patch, then install from a local copy of the charts
	  Boolean localCharts = false

          if (volthaHelmChartsChange != ''
              || gerritProject == 'voltha-helm-charts'
              || isReleaseBranch(branch) // branch != 'master'
          ) {
		localCharts = true
	  }
		    
		    String branchName = branchName()
		    Boolean is_release = isReleaseBranch(branch)
		    println([
			" ** localCharts=${localCharts}",
			"branchName=${branchName}",
			"branch=${branch}",
			"branch=isReleaseBranch=${is_release}",
		    ].join(', '))

		    // -----------------------------------------------------------------------
		    // Rewrite localHelmFlags using array join, moving code around and
		    // refactoring into standalone functions 
		    // -----------------------------------------------------------------------
		    // hudson.remoting.ProxyException: groovy.lang.MissingMethodException:
		    // No signature of method: java.lang.String.positive() is applicable for argument types: () values: []
		    // -----------------------------------------------------------------------
		    // NOTE temporary workaround expose ONOS node ports
		    // -----------------------------------------------------------------------
		    String localHelmFlags = [
			extraHelmFlags.trim(),
			"--set global.log_level=${logLevel.toUpperCase()}",
			'--set onos-classic.onosSshPort=30115',
			'--set onos-classic.onosApiPort=30120',
			'--set onos-classic.onosOfPort=31653',
			'--set onos-classic.individualOpenFlowNodePorts=true',
			testSpecificHelmFlags
		    ].join(' ')

		    println("** localHelmFlags = ${localHelmFlags}")

		    if (gerritProject != '') {
			localHelmFlags = "${localHelmFlags} " + getVolthaImageFlags("${gerritProject}")
		    }

		    println('volthaDeploy: ENTER')
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
		    println('volthaDeploy: LEAVE')

		} // script

        // -----------------------------------------------------------------------
        // Intent: Replacing P_IDS with pgrep/pkill is a step forward.
        // Why not simply use a pid file, capture _TAG=kail-startup above
        // Grep runs the risk of terminating stray commands (??-good <=> bad-??)
	// -----------------------------------------------------------------------
	script {
	    println('Try out pgrep/pkill commands')
            def stream = sh(
                returnStatus:false,
                returnStdout:true,
                script: '''pgrep --list-full kail-startup || true'''
            )
	    println("** pgrep output: ${stream}")
	}

        // -----------------------------------------------------------------------
        // stop logging
        // -----------------------------------------------------------------------
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
            script
            { 
		println('** setOnosLogLevels: ENTER')
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
		println('** setOnosLogLevels: LEAVE')
            } // script
        } // if (teardown)
    } // stage('Deploy Voltha')

    stage('Run test ' + testTarget + ' on ' + workflow + ' workFlow')
    {
        sh """
    if [ ${withMonitoring} = true ] ; then
      mkdir -p "$WORKSPACE/voltha-pods-mem-consumption-${workflow}"
      cd "$WORKSPACE/voltha-system-tests"
      make venv-activate-script
      set +u && source .venv/bin/activate && set -u
      # Collect initial memory consumption
      python scripts/mem_consumption.py -o $WORKSPACE/voltha-pods-mem-consumption-${workflow} -a 0.0.0.0:31301 -n ${volthaNamespace}
    fi
    """

        sh """
    mkdir -p ${logsDir}
    export ROBOT_MISC_ARGS="-d ${logsDir} ${params.extraRobotArgs} "
    ROBOT_MISC_ARGS+="-v ONOS_SSH_PORT:30115 -v ONOS_REST_PORT:30120 -v NAMESPACE:${volthaNamespace} -v INFRA_NAMESPACE:${infraNamespace} -v container_log_dir:${logsDir} -v logging:${testLogging}"
    export KVSTOREPREFIX=voltha/voltha_voltha

    make -C "$WORKSPACE/voltha-system-tests" ${testTarget}
    """

        getPodsInfo("${logsDir}")

        sh """
      # set +e
      # collect logs collected in the Robot Framework StartLogging keyword
      cd ${logsDir}
      gzip *-combined.log
      rm -f *-combined.log
    """

    sh """
    if [ ${withMonitoring} = true ] ; then
      cd "$WORKSPACE/voltha-system-tests"
      make venv-activate-script
      set +u && source .venv/bin/activate && set -u
      # Collect memory consumption of voltha pods once all the tests are complete
      python scripts/mem_consumption.py -o $WORKSPACE/voltha-pods-mem-consumption-${workflow} -a 0.0.0.0:31301 -n ${volthaNamespace}
    fi
    """
    } // stage

    return
} // execute_test()

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def collectArtifacts(exitStatus) {
    echo '''

** -----------------------------------------------------------------------
** collectArtifacts
** -----------------------------------------------------------------------
'''

  getPodsInfo("$WORKSPACE/${exitStatus}")

  sh """
  kubectl logs -n voltha -l app.kubernetes.io/part-of=voltha > $WORKSPACE/${exitStatus}/voltha.log
  """

  archiveArtifacts artifacts: '**/*.log,**/*.gz,**/*.txt,**/*.html,**/voltha-pods-mem-consumption-att/*,**/voltha-pods-mem-consumption-dt/*,**/voltha-pods-mem-consumption-tt/*'

  sh '''
    sync
    [[ $(pgrep --count kail) -gt 0 ]] && pkill --echo kail
    which voltctl
    md5sum $(which voltctl)
  '''

  step([$class: 'RobotPublisher',
    disableArchiveOutput: false,
    logFileName: '**/*/log*.html',
    otherFiles: '',
    outputFileName: '**/*/output*.xml',
    outputPath: '.',
    passThreshold: 100,
    reportFileName: '**/*/report*.html',
    unstableThreshold: 0,
    onlyCritical: true]);
}

// -----------------------------------------------------------------------
// Intent: main
// -----------------------------------------------------------------------
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
    DIAGS_PROFILE='VOLTHA_PROFILE'
    SSHPASS='karaf'
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

    stage('Build patch v1.1')
    {
        // build the patch only if gerritProject is specified
        when
        {
            expression
            {
                return !gerritProject.isEmpty()
            }
        }

        steps
        {
            // NOTE that the correct patch has already been checked out
            // during the getVolthaCode step
            buildVolthaComponent("${gerritProject}")
        }
    }

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage ('Install Kail')
    {
        steps
        {
            script
            {
	        String cmd = [
		    'make',
		    '--no-print-directory',
		    '-C', "$WORKSPACE/voltha-system-tests",
		    "KAIL_PATH=\"$WORKSPACE/bin\"",
		    'kail',
		].join(' ')

		println(" ** Running: ${cmd}:\n")
		sh("${cmd}")
	    } // script
        } // steps
    } // stage

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage ('Install Kind')
    {
        steps
        {
            script
            {

	        String cmd = [
			'make',
			'--no-print-directory',
			'-C', "$WORKSPACE/voltha-system-tests",
			"KIND_PATH=\"$WORKSPACE/bin\"",
			'install-command-kind',
		    ].join(' ')
		    
		println(" ** Running: ${cmd}:\n")
		    sh("${cmd}")
	    } // script
	} // steps
    } // stage

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage('Create K8s Cluster')
    {
        steps
        {
            script
            {
                def clusterExists = sh(
                    returnStdout: true,
                    script: """kind get clusters | grep "${clusterName}" | wc -l""")

                if (clusterExists.trim() == '0')
                {
                    createKubernetesCluster([nodes: 3, name: clusterName])
                }
            } // script
        } // steps
    } // stage('Create K8s Cluster')

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage('Replace voltctl')
    {
        // if the project is voltctl, override the downloaded one with the built one
        when {
            expression {
                return gerritProject == 'voltctl'
            }
        }

        // Hmmmm(?) where did the voltctl download happen ?
        // Likely Makefile but would be helpful to document here.
        steps
        {
            println("${iam} Running: installVoltctl($branch)")
            installVoltctl("$branch")
        } // steps
    } // stage

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage('Load image in kind nodes')
    {
        when {
            expression {
                return !gerritProject.isEmpty()
            }
        }
        steps {
            loadToKind()
        } // steps
    } // stage

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage('Parse and execute tests')
    {
        steps {
	    script {
                def tests = readYaml text: testTargets

                for(int i = 0;i<tests.size();i++) {
                    def test = tests[i]
                        def target = test['target']
                        def workflow = test['workflow']
                        def flags = test['flags']
                        def teardown = test['teardown'].toBoolean()
                        def logging = test['logging'].toBoolean()
                        def testLogging = 'False'
                        if (logging) {
                            testLogging = 'True'
                        }
                        println "Executing test ${target} on workflow ${workflow} with logging ${testLogging} and extra flags ${flags}"
                        execute_test(target, workflow, testLogging, teardown, flags)
                    } // for
                } // script
            } // steps
        } // stage
    } // stages

    post
    {
        aborted { collectArtifacts('aborted') }
        failure { collectArtifacts('failed')  }
        always  { collectArtifacts('always')  }
    }
} // pipeline

// EOF
