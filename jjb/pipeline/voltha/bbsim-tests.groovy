// -----------------------------------------------------------------------
// Copyright 2021-2024 Open Networking Foundation Contributors
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
// -----------------------------------------------------------------------
// SPDX-FileCopyrightText: 2021-2024 Open Networking Foundation Contributors
// SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------
// voltha-2.x e2e tests for openonu-go
// uses bbsim to simulate OLT/ONUs
// -----------------------------------------------------------------------

// [TODO] Update library() to the latest DSL syntax supported by jenkins
library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://gerrit.opencord.org/ci-management.git'
])

//------------------//
//---]  GLOBAL  [---//
//------------------//
String clusterName = 'kind-ci'

// -----------------------------------------------------------------------
// Intent: Return branch name for the script.  A hardcoded value is used
//   as a guarantee release jobs are running in an expected sandbox.
// -----------------------------------------------------------------------
String branchName() {
    String br = 'master'

    // "${branch}" is assigned by jenkins
    if (br != branch) {
        String err = [
            'ERROR: Detected invalid branch',
            "(expected=[${br}] != found=[${branch}])"
        ].join(' ')
        throw new Exception(err) // groovylint-disable-line ThrowException
    }

    return (br)
}

// -----------------------------------------------------------------------
// Intent: Due to lack of a reliable stack trace, construct a literal.
//         Jenkins will re-write the call stack for serialization.S
// -----------------------------------------------------------------------
// Note: Hardcoded version string used to visualize changes in jenkins UI
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
// Intent: Display a message with visibility for logging
// -----------------------------------------------------------------------
String banner(String message) {
    String iam = getIam('banner')

    println("""

** -----------------------------------------------------------------------
** IAM: $iam
** ${message}
** -----------------------------------------------------------------------
""")
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
// Intent: Terminate orphaned port-forward from different namespaces
// -----------------------------------------------------------------------
void cleanupPortForward() {
    enter('cleanupPortForward')

    Map pkpfArgs =\
    [
        'banner'     : true, // display banner for logging
        'show_procs' : true, // display procs under consideration
        'filler'     : true  // fix conditional trailing comma
    ]

    // 'kubectl.*port-forward'
    pkill_port_forward('port-forward', pkpfArgs)
    leave('cleanupPortForward')
    return
}

// find . \( -name 'log*.html' -o -name 'output*.xml' -o -name 'report*.html' \) -p
// -----------------------------------------------------------------------
// Intent: Display contents of the logs directory
// -----------------------------------------------------------------------
// [TODO]
//   o where-4-art-thou logs directory ?
//   o Replace find {logfile} command with /bin/ls {logdir} when found.
//     Individual logs may be missing due to failure, show what is available.
// -----------------------------------------------------------------------
void findPublishedLogs() {
    String iam = 'findPublishedLogs'

    enter(iam)
    sh(label  : iam,
       script : """
find . -name 'output.xml' -print
""")
    leave(iam)
    return
}

// -----------------------------------------------------------------------
// Intent: Terminate kail-startup process launched earlier
// -----------------------------------------------------------------------
// :param caller: Name of parent calling function (debug context)
// :type caller: String, optional
// :returns: none
// :rtype:   void
// -----------------------------------------------------------------------
void killKailStartup(String caller='') {
    String iam = "killKailStartup (caller=$caller)"

    enter(iam)
    sh(label  : 'Terminate kail-startup',
       script : """
if [[ \$(pgrep --count '_TAG=kail-startup') -gt 0 ]]; then
    pkill --uid \$(id -u) --echo --list-full --full '_TAG=kail-startup'
fi
""")
    leave(iam)
    return
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
    stage('Cleanup') {
        if (teardown) {
            timeout(15) {
                script {
                    helmTeardown(['default', infraNamespace, volthaNamespace])
                }
            } // timeout

            timeout(5) {
                script {
                    enter('Cleanup')
                    cleanupPortForward()
                    leave('Cleanup')
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

            sh(label  : 'Deploy common infrastructure',
               script : """
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

                    sh(label  : 'Launch kail-startup',
                       script : """
mkdir -p "$logsDir"
touch "$onosLog"

_TAG=kail-startup kail -n ${infraNamespace} -n ${volthaNamespace} > "$onosLog" &
""")

                    // if we're downloading a voltha-helm-charts patch,
                    // install from a local copy of the charts
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
                        localHelmFlags += getVolthaImageFlags(gerritProject)
                    }

                    enter('volthaDeploy')
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
                    leave('volthaDeploy')
                } // script

                // Display spawned procs
                script {
                    enter('bbsim-tests::pgrep_port_forward::0')
                    pgrep_port_forward('port-forw')
                    leave('bbsim-tests::pgrep_port_forward::0')

                    killKailStartup('Deploy Voltha')
                }

                // -----------------------------------------------------------------------
                // Bundle onos-voltha / kail logs
                // -----------------------------------------------------------------------
                sh(
                    label  : 'Bundle logs: onos-voltha-startup-combined',
                    script : """
cat <<EOM

** -----------------------------------------------------------------------
** Combine and compress voltha startup log(s)
** -----------------------------------------------------------------------
EOM

pushd "${logsDir}" || { echo "ERROR: pushd $logsDir failed"; exit 1; }
gzip -k onos-voltha-startup-combined.log
rm onos-voltha-startup-combined.log
popd               || { echo "ERROR: popd $logsDir failed"; exit 1; }
        """)
            } // timeout(10)

            // -----------------------------------------------------------------------
            // -----------------------------------------------------------------------
            sh(label  : 'while-true-port-forward',
               script : """
      JENKINS_NODE_COOKIE="dontKillMe" _TAG="voltha-infra-kafka" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${infraNamespace} svc/voltha-infra-kafka 9092:9092; done"&
      bbsimDmiPortFwd=50075
      for i in {0..${olts.toInteger() - 1}}; do
        JENKINS_NODE_COOKIE="dontKillMe" _TAG="bbsim\${i}" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${volthaNamespace} svc/bbsim\${i} \${bbsimDmiPortFwd}:50075; done"&
        ((bbsimDmiPortFwd++))
      done
      if [ ${withMonitoring} = true ] ; then
        JENKINS_NODE_COOKIE="dontKillMe" _TAG="nem-monitoring-prometheus-server" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n default svc/nem-monitoring-prometheus-server 31301:80; done"&
      fi
#      ps aux | grep port-forward
""")
            // ---------------------------------
            // Sanity check port-forward spawned
            // ---------------------------------
            script {
                enter('bbsim-tests::pgrep_port_forward::1')
                pgrep_port_forward('port-forw')
                leave('bbsim-tests::pgrep_port_forward::1')
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
                leave('setOnosLogLevels')
            } // script
        } // if (teardown)
    } // stage('Deploy Voltha')

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage("Run test ${testTarget} on workflow ${workflow}") {
        sh(
            label : 'Monitor using mem_consumption.py',
            script : """
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

  # Collect initial memory consumption
  set +u && source .venv/bin/activate && set -u
  python scripts/mem_consumption.py -o $WORKSPACE/voltha-pods-mem-consumption-${workflow} -a 0.0.0.0:31301 -n ${volthaNamespace}
fi

echo -e '** Monitor memory consumption: LEAVE\n'
""")

        sh(
            label  : "make testTarget=[${testTarget}]",
            script : """
echo -e "\n** make testTarget=[${testTarget}]"
mkdir -p ${logsDir}
export ROBOT_MISC_ARGS="-d ${logsDir} ${params.extraRobotArgs} "
ROBOT_MISC_ARGS+="-v ONOS_SSH_PORT:30115 -v ONOS_REST_PORT:30120 -v NAMESPACE:${volthaNamespace} -v INFRA_NAMESPACE:${infraNamespace} -v container_log_dir:${logsDir} -v logging:${testLogging}"
export KVSTOREPREFIX=voltha/voltha_voltha

make -C "$WORKSPACE/voltha-system-tests" ${testTarget}
""")

        getPodsInfo("${logsDir}")

        // [TODO] make conditional, bundle when logs are available
        sh(
            label : 'Gather robot Framework logs',
            script : """
echo -e '\n** Gather robot Framework logs: ENTER'

# set +e
# collect logs collected in the Robot Framework StartLogging keyword
cd "${logsDir}"

echo "** Available logs:"
/bin/ls -l "$logsDir"
echo

echo '** Bundle combined log'
gzip *-combined.log || true
rm -f *-combined.log || true

echo -e '** Gather robot Framework logs: LEAVE\n'
""")

        // -----------------------------------------------------------------------
        // -----------------------------------------------------------------------
        sh(
            label  : 'Monitor pod-mem-consumption',
            script : """
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

# Collect memory consumption of voltha pods once all the tests are complete
set +u && source .venv/bin/activate && set -u
python scripts/mem_consumption.py -o $WORKSPACE/voltha-pods-mem-consumption-${workflow} -a 0.0.0.0:31301 -n ${volthaNamespace}
fi
echo -e '** Monitor pod-mem-consumption: LEAVE\n'
""")
    } // stage

    return
} // execute_test()

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
void collectArtifacts(exitStatus) {
    script {
        enter("exitStatus=${exitStatus}")
        banner('collectArtifacts')
    }

    getPodsInfo("$WORKSPACE/${exitStatus}")

    sh(label  : 'kubectl logs > voltha.log',
       script : """
kubectl logs -n voltha -l app.kubernetes.io/part-of=voltha \
    > $WORKSPACE/${exitStatus}/voltha.log
""")

    archiveArtifacts artifacts: '**/*.log,**/*.gz,**/*.txt,**/*.html,**/voltha-pods-mem-consumption-att/*,**/voltha-pods-mem-consumption-dt/*,**/voltha-pods-mem-consumption-tt/*'

    script { killKailStartup('collectArtifacts') }
    script { findPublishedLogs() }

    enter('RobotPublisher')
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
    leave('RobotPublisher')

    leave("exitStatus=${exitStatus}")
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
                enter('getVolthaCode')
                getVolthaCode([
                    branch: "${branch}",
                    gerritProject: "${gerritProject}",
                    gerritRefspec: "${gerritRefspec}",
                    volthaSystemTestsChange: "${volthaSystemTestsChange}",
                    volthaHelmChartsChange: "${volthaHelmChartsChange}",
                ])
                leave('getVolthaCode')
            }
        }

        stage('Build patch v1.1') {
            // build the patch only if gerritProject is specified
            when {
                expression { return !gerritProject.isEmpty() }
            }

            steps {
                enter('buildVolthaComponent')
                // NOTE that the correct patch has already been checked out
                // during the getVolthaCode step
                buildVolthaComponent("${gerritProject}")
                leave('buildVolthaComponent')
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
        stage('Install kubectl')
        {
            steps
            {
                script
                {
                    String cmd = [
                        'make',
                        '--no-print-directory',
                        '-C', "$WORKSPACE",
                        "KUBECTL_PATH=\"$WORKSPACE/bin\"",
                        'kubectl',
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
                        label : 'Create K8s Cluster',
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

                    def tests = readYaml text: testTargets // typeof == Map (?)
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
                            enter("execute_test (target=$target)")
                            execute_test(target, workflow, testLogging, teardown, flags)
                        }
                        // groovylint-disable-next-line CatchException
                        catch (Exception err) {
                            String iamexc = getIam(test)
                            println("** ${iamexc}: EXCEPTION ${err}")
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
    { // https://www.jenkins.io/doc/book/pipeline/syntax/#post
        aborted {
            collectArtifacts('aborted')
        }
        failure {
            collectArtifacts('failed')
        }
        always {
            collectArtifacts('always')
        }
        cleanup {
            script { cleanupPortForward() }
        }
    }
} // pipeline

// [EOF]
