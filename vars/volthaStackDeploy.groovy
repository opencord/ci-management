#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Copyright 2021-2024 Open Networking Foundation (ONF) and the ONF Contributors
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

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
String getIam(String func) {
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/volthaStackDeploy.groovy'
    String iam = [src, func].join('::')
    return iam
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
// Intent:
// -----------------------------------------------------------------------
void deployVolthaStack(Map cfg) {
    enter('deployVolthaStack')

    sh(label  : "Create VOLTHA Stack ${cfg.stackName}, (namespace=${cfg.volthaNamespace})",
       script : """

helm upgrade --install --create-namespace \
          -n ${cfg.volthaNamespace} ${cfg.stackName} ${cfg.volthaStackChart} \
          --set global.stack_name=${cfg.stackName} \
          --set global.voltha_infra_name=voltha-infra \
          --set voltha.onos_classic.replicas=${cfg.onosReplica} \
          --set voltha.ingress.enabled=true \
          --set voltha.ingress.hosts[0].host=voltha.${cfg.cluster} \
          --set voltha.ingress.hosts[0].paths[0]='/voltha.VolthaService/' \
          --set global.voltha_infra_namespace=${cfg.infraNamespace} \
          ${cfg.extraHelmFlags}
""")

    for (int i = 0; i < cfg.bbsimReplica; i++) {
        // NOTE we don't need to update the tag for DT
        script {
            sh(label  : "Create config[$i]: bbsimCfg${cfg.stackId}${i}.yaml",
               script : "rm -f $WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml",
            )

            if (cfg.workflow == 'att' || cfg.workflow == 'tt') {
                int startingStag = 900
                def serviceConfigFile = cfg.workflow // type(?) String
                if (cfg.withMacLearning && cfg.workflow == 'tt') {
                    serviceConfigFile = 'tt-maclearner'
                }
                // bbsimCfg: type(?) Map
                def bbsimCfg = readYaml file: "$WORKSPACE/voltha-helm-charts/examples/${serviceConfigFile}-values.yaml"
                // NOTE we assume that the only service that needs a different s_tag is the first one in the list
                bbsimCfg['servicesConfig']['services'][0]['s_tag'] = startingStag + i
                println "Using BBSim Service config ${bbsimCfg['servicesConfig']}"
                writeYaml file: "$WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml", data: bbsimCfg
            } else {
                // NOTE if it's DT just copy the file over
                sh(label  : 'DT install',
                   script : """
cp $WORKSPACE/voltha-helm-charts/examples/${cfg.workflow}-values.yaml \
   $WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml
          """)
            } // if (cfg)
        } // script

        sh(label  : "HELM: Create namespace=${cfg.volthaNamespace} bbsim${i}",
           script :  """
        helm upgrade --install --create-namespace -n ${cfg.volthaNamespace} bbsim${i} ${cfg.bbsimChart} \
        --set olt_id="${cfg.stackId}${i}" \
        -f $WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml \
        ${cfg.extraHelmFlags}
""")
    } // for

    leave('deployVolthaStack')
    return
}

// -----------------------------------------------------------------------
// Intent: Wait until the pod completed, meaning ONOS fully deployed
// -----------------------------------------------------------------------
// Todo: Move logic like this into a standalone script.
//   Use jenkins stash or native JJB logic to publish out to nodes.
// -----------------------------------------------------------------------
void launchVolthaStack(Map cfg) {
    enter('launchVolthaStack')

    /* -----------------------------------------------------------------------
     * % kubectl get pods
     17:40:15  bbsim0-868479698c-z66mk                          0/1   ContainerCreating   0     6s
     17:40:15  voltha-voltha-adapter-openolt-68c84bf786-z98rh   0/1   Running             0     8s

     * % kubectl port-forward --address 0.0.0.0
     17:40:15  error: unable to forward port because pod is not running. Current status=Pending
     * -----------------------------------------------------------------------
     */
    sh(label  : "Wait for VOLTHA Stack (stack=${cfg.stackName}, namespace=${cfg.volthaNamespace}) to start",
       script : """

cat <<EOM

** -----------------------------------------------------------------------
** Wait for VOLTHA Stack (stack=${cfg.stackName}, namespace=${cfg.volthaNamespace}) to start
** -----------------------------------------------------------------------
EOM

# set -euo pipefail
set +x #                 # Logs are noisy with set -x

declare -i count=0
declare -i debug=1       # uncomment to enable debugging
# declare -i verbose=1   # uncomment to enable debugging
vsd_log='volthaStackDeploy.tmp'
echo > \$vsd_log

declare -i rc=0          # exit status
while true; do

    # Gather
    kubectl get pods -n ${cfg.volthaNamespace} \
        -l app.kubernetes.io/part-of=voltha --no-headers \
        > \$vsd_log

    count=\$((\$count - 1))

    # Display activity every iteration ?
    [[ -v verbose ]] && { count=0; }

    # Display activity every minute or so {sleep(5) x count=10}
    if [[ \$count -lt 1 ]]; then
        count=10
        cat \$vsd_log
    fi

    ## -----------------------
    ## Probe for cluster state
    ## -----------------------
    if grep -q -e 'ContainerCreating' \$vsd_log; then
        echo -e '\nvolthaStackDeploy.groovy: ContainerCreating active'
        [[ -v debug ]] && grep -e 'ContainerCreating' \$vsd_log

    elif grep -q -e '0/' \$vsd_log; then
        echo -e '\nvolthaStackDeploy.groovy: Waiting for status=Running'
        [[ -v debug ]] && grep -e '0/' \$vsd_log

    elif ! grep -q '/' \$vsd_log; then
        echo -e '\nvolthaStackDeploy.groovy: Waiting for initial pod activity'
        [[ ! -v verbose ]] && { cat \$vsd_log; }

    # -----------------------------------------------------------------------
    # voltha-adapter-openolt-68c84bf786-8xsfc   0/1   CrashLoopBackOff 2 69s
    # voltha-adapter-openolt-68c84bf786-8xsfc   0/1   Error            3 85s
    # -----------------------------------------------------------------------
    elif grep -q 'Error' \$vsd_log; then
        echo -e '\nvolthaStackDeploy.groovy: Detected cluster state=Error'
        cat \$vsd_log
        rc=1 # fatal
        break

    # -----------------------------------------------------------------------
    # An extra conditon needed here but shell coding is tricky:
    #    "svc x/y Running 0 6s
    #    Verify (x == y) && (x > 0)
    # Wait until job failure/we have an actual need for it.
    # -----------------------------------------------------------------------
    # Could check for all services 'Running', is that reliable (?)
    # -----------------------------------------------------------------------
    else
        echo -e '\nvolthaStackDeploy.groovy: Voltha stack has launched'
        [[ ! -v verbose ]] && { cat \$vsd_log; }
        break
    fi

    ## Support argument --timeout (?)
    sleep 5

done
rm -f \$vsd_log
exit \$rc
""")

    leave('launchVolthaStack')
    return
}

// -----------------------------------------------------------------------
// Intent: Wait until the pod completed, meaning ONOS fully deployed
// -----------------------------------------------------------------------
void waitForOnosDeploy(Map cfg) {
    enter('waitForOnosDeploy')

    sh(label  : 'Wait for ONOS full deployment',
       script : """

cat <<EOM

** -----------------------------------------------------------------------
** Wait for ONOS full deployment
** -----------------------------------------------------------------------
EOM

# set -euo pipefail
set +x #        # Noisy when commented (default: uncommented)

declare -i count=0
declare -i debug=1       # uncomment to enable debugging
declare -i verbose=1     # uncomment to enable debugging
vsd_log='volthaStackDeploy.tmp'
echo > \$vsd_log

declare -i rc=0          # exit status
while true; do

    # Gather -- should we check for count(svc > 1) ?
    kubectl get pods -l app=onos-config-loader \
        -n ${cfg.infraNamespace} --no-headers \
        --field-selector=status.phase=Running \
        > \$vsd_log

    count=\$((\$count - 1))

    # Display activity every iteration ?
    [[ -v verbose ]] && { count=0; }

    # Display activity every minute or so {sleep(5) x count=10}
    if [[ \$count -lt 1 ]]; then
        count=10
        cat \$vsd_log
    fi

    ## -----------------------
    ## Probe for cluster state
    ## -----------------------
    if grep -q -e 'ContainerCreating' \$vsd_log; then
        echo -e '\nvolthaStackDeploy.groovy: ContainerCreating active'
        [[ -v debug ]] && grep -e 'ContainerCreating' \$vsd_log

    elif grep -q -e '0/' \$vsd_log; then
        echo -e '\nvolthaStackDeploy.groovy: Waiting for status=Running'
        [[ -v debug ]] && grep -e '0/' \$vsd_log

    elif ! grep -q '/' \$vsd_log; then
        echo -e '\nvolthaStackDeploy.groovy: Waiting for initial pod activity'
        [[ ! -v verbose ]] && { cat \$vsd_log; }

    # -----------------------------------------------------------------------
    # voltha-adapter-openolt-68c84bf786-8xsfc   0/1   CrashLoopBackOff 2 69s
    # voltha-adapter-openolt-68c84bf786-8xsfc   0/1   Error            3 85s
    # -----------------------------------------------------------------------
    elif grep -q 'Error' \$vsd_log; then
        echo -e '\nvolthaStackDeploy.groovy: Detected cluster state=Error'
        cat \$vsd_log
        rc=1 # fatal
        break

    # -----------------------------------------------------------------------
    # An extra conditon needed here but shell coding is tricky:
    #    "svc x/y Running 0 6s
    #    Verify (x == y) && (x > 0)
    # Wait until job failure/we have an actual need for it.
    # -----------------------------------------------------------------------
    # Could check for all services 'Running', is that reliable (?)
    # -----------------------------------------------------------------------
    else
        echo -e '\nvolthaStackDeploy.groovy: Voltha stack has launched'
        [[ ! -v verbose ]] && { cat \$vsd_log; }
        break
    fi

    ## Support argument --timeout (?)
    sleep 5

done
rm -f \$vsd_log
exit \$rc
""")

    leave('waitForOnosDeploy')
    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
void process(Map config) {
    enter('process')

    // note that I can't define this outside the function as there's no global scope in Groovy
    Map defaultConfig = [
        bbsimReplica:    1,
        infraNamespace:  'infra',
        volthaNamespace: 'voltha',
        stackName:       'voltha',
        stackId: 1, // NOTE this is used to differentiate between BBSims across multiple stacks
        workflow:        'att',
        withMacLearning: false,
        withFttb:        false,
        extraHelmFlags:  '',
        localCharts:     false,
        onosReplica:     1,
        adaptersToWait:  2,
    ]

    Map cfg = defaultConfig + config

    // Augment config map
    cfg.volthaStackChart = 'onf/voltha-stack'
    cfg.bbsimChart       = 'onf/bbsim'

    if (cfg.localCharts) {
        cfg.volthaStackChart = "$WORKSPACE/voltha-helm-charts/voltha-stack"
        cfg.bbsimChart       = "$WORKSPACE/voltha-helm-charts/bbsim"

        sh(label  : 'HELM: Update voltha-stack deps',
           script : """
      pushd $WORKSPACE/voltha-helm-charts/voltha-stack
      helm dep update
      popd
""")
    }

    println "Deploying VOLTHA Stack with the following parameters: ${cfg}."
    deployVolthaStack(cfg)
    launchVolthaStack(cfg)
    waitForAdapters(cfg)
    waitForOnosDeploy(cfg)
    leave('process')

    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(Map config=[:]) { // Function return type(?)
    try {
        enter('main')
        process(config)
    }
    catch (Exception err) {  // groovylint-disable-line CatchException
        ans = false
        println("** volthaStackDeploy.groovy: EXCEPTION ${err}")
        throw err
    }
    finally {
        leave('main')
    }
    return
}

// [EOF]
