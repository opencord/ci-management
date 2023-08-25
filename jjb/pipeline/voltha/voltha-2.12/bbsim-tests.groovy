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
    String name = 'voltha-2.12'

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
    String version = '757ba9ea12d8d815c10301b7f265f3fcd7c41d26'
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

// -----------------------------------------------------------------------
// Intent: Log progress message
// -----------------------------------------------------------------------
void enter(String name) {
    // Announce ourselves for log usability
    String iam = getIam(name)
    println("${iam}: ENTER")
    return
}

// -----------------------------------------------------------------------
// Intent: Log progress message
// -----------------------------------------------------------------------
void leave(String name) {
    // Announce ourselves for log usability
    String iam = getIam(name)
    println("${iam}: LEAVE")
    return
}

// -----------------------------------------------------------------------
// Intent: Determine if working on a release branch.
//   Note: Conditional is legacy, should also check for *-dev or *-pre
// -----------------------------------------------------------------------
Boolean isReleaseBranch(String name) {
    // List modifiers = ['-dev', '-pre', 'voltha-x.y.z-pre']
    // if branchName in modifiers
    return(name != 'master') // OR branchName.contains('-')
}

// -----------------------------------------------------------------------
// Intent: Iterate over a list of test suites and invoke.
// -----------------------------------------------------------------------
void execute_test\
(
    String  testTarget,                       // functional-single-kind-dt
    String  workflow,                         // dt
    String  testLogging,                      // 'True'
    Boolean teardown,                         // true
    String  testSpecificHelmFlags=''
) {
    String infraNamespace  = 'default'
    String volthaNamespace = 'voltha'
    String logsDir = "$WORKSPACE/${testTarget}"

    // -----------------------------------------------------------------------
    // Intent: Cleanup stale port-forwarding
    // -----------------------------------------------------------------------
    stage('Cleanup')    {
        if (teardown)   {
            timeout(15) {
                script  {
                    helmTeardown(['default', infraNamespace, volthaNamespace])
                }
            } // timeout

            timeout(5) {
                script {
                    enter('Cleanup')
                    // remove orphaned port-forward from different namespaces
                    String proc = 'port-forw'
                    pgrep_proc(proc)
                    pkill_proc(proc)
                    pgrep_proc(proc) // proc count == 0
                    enter('Cleanup')
                } // script
            } // timeout
        } // teardown
    } // stage('Cleanup')

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage('Deploy common infrastructure') {
        script {
            local dashargs = [
                'kpi_exporter.enabled=false',
                'dashboards.xos=false',
                'dashboards.onos=false',
                'dashboards.aaa=false',
                'dashboards.voltha=false',
            ].join(',')

            local promargs = [
                'prometheus.alertmanager.enabled=false',
                'prometheus.pushgateway.enabled=false',
            ].join(',')

            sh("""
    helm repo add onf https://charts.opencord.org
    helm repo update

    echo -e "\nwithMonitoring=[$withMonitoring]"
    if [ ${withMonitoring} = true ] ; then
      helm install nem-monitoring onf/nem-monitoring \
          --set ${promargs} \
          --set ${dashargs}
    fi
    """)
        } // script
    } // stage('Deploy Common Infra')

    // -----------------------------------------------------------------------
    // [TODO] Check onos_log output
    // -----------------------------------------------------------------------
    stage('Deploy Voltha') {
        if (teardown)      {
            timeout(10)    {
                script     {
                    String iam = getIam('Deploy Voltha')
                    String onosLog = "${logsDir}/onos-voltha-startup-combined.log"
                    sh("""
          mkdir -p ${logsDir}
          touch "$onosLog"
          echo "** kail-startup ENTER: \$(date)" > "$onosLog"

          # Intermixed output (tee -a &) may get conflusing but let(s) see
          # what messages are logged during startup.
          #  _TAG=ka7il-startup kail -n ${infraNamespace} -n ${volthaNamespace} >> "$onosLog" &
          _TAG=kail-startup kail -n ${infraNamespace} -n ${volthaNamespace} | tee -a "$onosLog" &
          """)

                    // if we're downloading a voltha-helm-charts patch, then install from a local copy of the charts
                    Boolean localCharts = false

                    if (volthaHelmChartsChange != ''
                        || gerritProject == 'voltha-helm-charts'
                        || isReleaseBranch(branch) // branch != 'master'
                    ) {
                        localCharts = true
                    }

                    String branchName = branchName()
                    Boolean isRelease = isReleaseBranch(branch)
                    println([
                        " ** localCharts=${localCharts}",
                        "branchName=${branchName}",
                        "branch=${branch}",
                        "branch=isReleaseBranch=${isRelease}",
                    ].join(', '))

                    // -----------------------------------------------------------------------
                    // Rewrite localHelmFlags using array join, moving code around and
                    // refactoring into standalone functions
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

                    println("** ${iam} localHelmFlags = ${localHelmFlags}")

                    if (gerritProject != '') {
                        localHelmFlags = "${localHelmFlags} " + getVolthaImageFlags("${gerritProject}")
                    }

                    println("** ${iam}: ENTER")
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
                    println("** ${iam}: LEAVE")
                } // script

                // -----------------------------------------------------------------------
                // Intent: Replacing P_IDS with pgrep/pkill is a step forward.
                // Why not simply use a pid file, capture _TAG=kail-startup above
                // Grep runs the risk of terminating stray commands (??-good <=> bad-??)
                // -----------------------------------------------------------------------
                script {
                    String proc = '_TAG=kail-startup'

                    println("${iam}: ENTER")
                    println("${iam}: Shutdown process $proc")
                    pgrep_proc(proc)
                    pkill_proc(proc)
                    pgrep_proc(proc)
                    println("${iam}: LEAVE")
                }

                // -----------------------------------------------------------------------
                // Bundle onos-voltha / kail logs
                // -----------------------------------------------------------------------
                sh("""
cat <<EOM

** -----------------------------------------------------------------------
** Combine an compress voltha startup log(s)
** -----------------------------------------------------------------------
EOM
          pushd "${logsDir}" || { echo "ERROR: pushd $logsDir failed"; exit 1; }
          gzip -k onos-voltha-startup-combined.log
          rm onos-voltha-startup-combined.log
          popd
        """)
        } // timeout(10)

            // -----------------------------------------------------------------------
            // -----------------------------------------------------------------------
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
            // ---------------------------------
            // Sanity check port-forward spawned
            // ---------------------------------
            script {
                enter('port-forward check')
                String proc = 'port-forward'
                println("Display spawned ${proc}")
                pgrep_proc(proc)
                leave('port-forward check')
            }

            // setting ONOS log level
            script {
                enter('setOnosLogLevels')
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
                enter('setOnosLogLevels')
            } // script
        } // if (teardown)
    } // stage('Deploy Voltha')

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage("Run test ${testTarget} on workflow ${workflow}")
    {
        sh """
        echo -e "\n** Monitor using mem_consumption.py ?"

    if [ ${withMonitoring} = true ] ; then
      cat <<EOM

** -----------------------------------------------------------------------
** Monitoring memory usage with mem_consumption.py
** -----------------------------------------------------------------------
EOM
      mkdir -p "$WORKSPACE/voltha-pods-mem-consumption-${workflow}"
      cd "$WORKSPACE/voltha-system-tests"

      echo '** Installing python virtualenv'
      make venv-activate-patched

      set +u && source .venv/bin/activate && set -u
      # Collect initial memory consumption
      python scripts/mem_consumption.py -o $WORKSPACE/voltha-pods-mem-consumption-${workflow} -a 0.0.0.0:31301 -n ${volthaNamespace}
    fi

    echo -e '** Monitor memory consumption: LEAVE\n'
    """

        sh """
        echo -e "\n** make testTarget=[${testTarget}]"
    mkdir -p ${logsDir}
    export ROBOT_MISC_ARGS="-d ${logsDir} ${params.extraRobotArgs} "
    ROBOT_MISC_ARGS+="-v ONOS_SSH_PORT:30115 -v ONOS_REST_PORT:30120 -v NAMESPACE:${volthaNamespace} -v INFRA_NAMESPACE:${infraNamespace} -v container_log_dir:${logsDir} -v logging:${testLogging}"
    export KVSTOREPREFIX=voltha/voltha_voltha

    make -C "$WORKSPACE/voltha-system-tests" ${testTarget}
    """

        getPodsInfo("${logsDir}")

        sh """
      echo -e '\n** Gather robot Framework logs: ENTER'
      # set +e
      # collect logs collected in the Robot Framework StartLogging keyword
      cd ${logsDir}
      gzip *-combined.log
      rm -f *-combined.log

      echo -e '** Gather robot Framework logs: LEAVE\n'
    """

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    sh """
    echo -e '** Monitor pod-mem-consumption: ENTER'
    if [ ${withMonitoring} = true ] ; then
      cat <<EOM

** -----------------------------------------------------------------------
** Monitoring pod-memory-consumption using mem_consumption.py
** -----------------------------------------------------------------------
EOM
      cd "$WORKSPACE/voltha-system-tests"

      echo '** Installing python virtualenv'
      make venv-activate-patched

      set +u && source .venv/bin/activate && set -u
      # Collect memory consumption of voltha pods once all the tests are complete
      python scripts/mem_consumption.py -o $WORKSPACE/voltha-pods-mem-consumption-${workflow} -a 0.0.0.0:31301 -n ${volthaNamespace}
    fi
    echo -e '** Monitor pod-mem-consumption: LEAVE\n'
    """
    } // stage

    return
} // execute_test()

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def collectArtifacts(exitStatus) {
    script {
        String iam = getIam('collectArtifacts')
        println("${iam}: ENTER (exitStatus=${exitStatus})")
    }

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

    script {
        println("${iam}: ENTER")
        pgrep_proc('kail-startup')
        pkill_proc('kail')
        println("${iam}: LEAVE")
    }

    println("${iam}: ENTER RobotPublisher")
    step([$class: 'RobotPublisher',
          disableArchiveOutput: false,
          logFileName: '**/*/log*.html',
          otherFiles: '',
          outputFileName: '**/*/output*.xml',
          outputPath: '.',
          passThreshold: 100,
          reportFileName: '**/*/report*.html',
          unstableThreshold: 0,
          onlyCritical: true])
    println("${iam}: LEAVE RobotPublisher")

    println("${iam}: LEAVE (exitStatus=${exitStatus})")
    return
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
        KUBECONFIG = "$HOME/.kube/kind-${clusterName}"
        VOLTCONFIG = "$HOME/.volt/config"
        PATH = "$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        DIAGS_PROFILE = 'VOLTHA_PROFILE'
        SSHPASS = 'karaf'
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

        stage('Build patch v1.1') {
            // build the patch only if gerritProject is specified
            when {
                expression { return !gerritProject.isEmpty() }
            }

            steps {
                // NOTE that the correct patch has already been checked out
                // during the getVolthaCode step
                buildVolthaComponent("${gerritProject}")
            }
        }

        // -----------------------------------------------------------------------
        // -----------------------------------------------------------------------
        stage('Install Kail')
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

                    println(" ** Running: ${cmd}")
                    sh("${cmd}")
                } // script
            } // steps
        } // stage

        // -----------------------------------------------------------------------
        // -----------------------------------------------------------------------
        stage('Install Tools') {
            steps              {
                script         {
                    String branchName = branchName()
                    String iam = getIam('Install Tools')

                    println("${iam}: ENTER (branch=$branch)")
                    installKind(branch)   // needed early by stage(Cleanup)
                    println("${iam}: LEAVE (branch=$branch)")
                } // script
            } // steps
        } // stage

        // -----------------------------------------------------------------------
        // -----------------------------------------------------------------------
        stage('Create K8s Cluster') {
            steps {
                script {
                    def clusterExists = sh(
                        returnStdout: true,
                        script: """kind get clusters | grep "${clusterName}" | wc -l""")

                    if (clusterExists.trim() == '0') {
                        createKubernetesCluster([nodes: 3, name: clusterName])
                    }
                } // script
            } // steps
        } // stage('Create K8s Cluster')

        // -----------------------------------------------------------------------
        // -----------------------------------------------------------------------
        stage('Replace voltctl') {
            // if the project is voltctl, override the downloaded one with the built one
            when {
                expression { return gerritProject == 'voltctl' }
            }

            // Hmmmm(?) where did the voltctl download happen ?
            // Likely Makefile but would be helpful to document here.
            steps {
                script {
                    String iam = getIam('Replace voltctl')

                    println("${iam} Running: installVoltctl($branch)")
                    println("${iam}: ENTER")
                    installVoltctl("$branch")
                    println("${iam}: LEAVE")
                } // script
            } // step
        } // stage

        // -----------------------------------------------------------------------
        // -----------------------------------------------------------------------
        stage('Load image in kind nodes')
        {
            when {
                expression { return !gerritProject.isEmpty() }
            }
            steps {
                loadToKind()
            } // steps
        } // stage

        // -----------------------------------------------------------------------
        // [TODO] verify testing output
        // -----------------------------------------------------------------------
        stage('Parse and execute tests')
        {
            steps {
                script {
                    // Announce ourselves for log usability
                    enter('Parse and execute tests')

                    def tests = readYaml text: testTargets
                    println("** [DEBUG]: tests=$tests")

                    // Display expected tests for times when output goes dark
                    tests.eachWithIndex { test, idx ->
                        String  target = test['target']
                        println("**      test[${idx}]: ${target}\n")
                    }

                    println('''
** -----------------------------------------------------------------------
** NOTE: For odd/silent job failures verify a few details
**   - All tests mentioned in the tests-to-run index were logged.
**   - Test suites display ENTER/LEAVE mesasge pairs.
**   - Processing terminated prematurely when LEAVE strings are missing.
** -----------------------------------------------------------------------
''')
                    tests.eachWithIndex { test, idx ->
                        println "** readYaml test suite[$idx]) test=[${test}]"

                        String  target      = test['target']
                        String  workflow    = test['workflow']
                        String  flags       = test['flags']
                        Boolean teardown    = test['teardown'].toBoolean()
                        Boolean logging     = test['logging'].toBoolean()
                        String  testLogging = (logging) ? 'True' : 'False'

                        print("""
** -----------------------------------------------------------------------
** Executing test ${target} on workflow ${workflow} with logging ${testLogging} and extra flags ${flags}
** -----------------------------------------------------------------------
""")

                        try {
                            leave("execute_test (target=$target)")
                            execute_test(target, workflow, testLogging, teardown, flags)
                        }
                        catch (Exception err) {
                            println("** ${iam}: EXCEPTION ${err}")
                        }
                        finally {
                            leave("execute_test (target=$target)")
                        }
                    } // for
                    // Premature exit if this message is not logged
                    leave('Parse and execute tests')
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

// [EOF] - 2
