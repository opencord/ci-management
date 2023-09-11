#!/usr/bin/env groovy
// -----------------------------------------------------------------------
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
          --set global.voltha_infra_namespace=${cfg.infraNamespace} \
          ${cfg.extraHelmFlags}
""")

    for (int i = 0; i < cfg.bbsimReplica; i++) {
        // NOTE we don't need to update the tag for DT
        script {
            sh(label  : "Create config[$i]: bbsimCfg${cfg.stackId}${i}.yaml",
               script : "rm -f $WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml",
            )

            if (c1fg.workflow == 'att' || cfg.workflow == 'tt') {
                int startingStag = 900
                def serviceConfigFile = cfg.workflow
                if (cfg.withMacLearning && cfg.workflow == 'tt') {
                    serviceConfigFile = 'tt-maclearner'
                }
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
void launchVolthaStack(Map cfg) {
    enter('launchVolthaStack')

    sh(label   : "Wait for VOLTHA Stack ${cfg.stackName}::${cfg.volthaNamespace} to start",
       script : """

cat <<EOM

** -----------------------------------------------------------------------
** Wait for VOLTHA Stack ${cfg.stackName}::${cfg.volthaNamespace} to start
** -----------------------------------------------------------------------
EOM

# set -euo pipefail
set +x #        # Noisy when commented (default: uncommented)

declare -i count=0
vsd_log='volthaStackDeploy.tmp'
touch \$vsd_log
while true; do

    ## Exit when the server begins showing signs of life
    if grep -q '0/' \$vsd_log; then
        echo 'volthaStackDeploy.groovy: Detected kubectl pods =~ 0/'
        grep '0/' \$vsd_log
        break
    fi

    sleep 5
    count=\$((\$count - 1))

    if [[ \$count -lt 1 ]]; then # [DEBUG] Display activity every minute or so
        count=10
        kubectl get pods -n ${cfg.volthaNamespace} \
            -l app.kubernetes.io/part-of=voltha --no-headers \
            | tee \$vsd_log
    else
        kubectl get pods -n ${cfg.volthaNamespace} \
            -l app.kubernetes.io/part-of=voltha --no-headers \
            > \$vsd_log
    fi

done
rm -f \$vsd_log
""")

    leave('launchVolthaStack')
    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def process(Map config) {
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
    leave('process')

    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(Map config=[:]) {
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
