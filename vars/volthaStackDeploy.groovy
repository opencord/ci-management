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
String getIam(String func)
{
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
// -----------------------------------------------------------------------
def process(Map config)
{
    enter('process')

    // note that I can't define this outside the function as there's no global scope in Groovy
    def defaultConfig = [
        bbsimReplica: 1,
        infraNamespace: "infra",
        volthaNamespace: "voltha",
        stackName: "voltha",
        stackId: 1, // NOTE this is used to differentiate between BBSims across multiple stacks
        workflow: "att",
        withMacLearning: false,
        withFttb: false,
        extraHelmFlags: "",
        localCharts: false,
        onosReplica: 1,
        adaptersToWait: 2,
    ]
    
    def cfg = defaultConfig + config
    
    def volthaStackChart = "onf/voltha-stack"
    def bbsimChart = "onf/bbsim"
    
    if (cfg.localCharts) {
        volthaStackChart = "$WORKSPACE/voltha-helm-charts/voltha-stack"
        bbsimChart = "$WORKSPACE/voltha-helm-charts/bbsim"
        
        sh """
      pushd $WORKSPACE/voltha-helm-charts/voltha-stack
      helm dep update
      popd
      """
    }
    
    println "Deploying VOLTHA Stack with the following parameters: ${cfg}."
    
    sh(label  : "Create VOLTHA Stack ${cfg.stackName}, (namespace=${cfg.volthaNamespace})",
       script : """
    helm upgrade --install --create-namespace -n ${cfg.volthaNamespace} ${cfg.stackName} ${volthaStackChart} \
          --set global.stack_name=${cfg.stackName} \
          --set global.voltha_infra_name=voltha-infra \
          --set voltha.onos_classic.replicas=${cfg.onosReplica} \
          --set global.voltha_infra_namespace=${cfg.infraNamespace} \
          ${cfg.extraHelmFlags}
    """)

    for(int i = 0;i<cfg.bbsimReplica;i++) {
        // NOTE we don't need to update the tag for DT
        script {
            sh(label  : "Create config[$i]: bbsimCfg${cfg.stackId}${i}.yaml",
               script : "rm -f $WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml",
            )
            
            if (cfg.workflow == "att" || cfg.workflow == "tt") {
                def startingStag = 900
                def serviceConfigFile = cfg.workflow
                if (cfg.withMacLearning && cfg.workflow == 'tt') {
                    serviceConfigFile = "tt-maclearner"
                }
                def bbsimCfg = readYaml file: "$WORKSPACE/voltha-helm-charts/examples/${serviceConfigFile}-values.yaml"
                // NOTE we assume that the only service that needs a different s_tag is the first one in the list
                bbsimCfg["servicesConfig"]["services"][0]["s_tag"] = startingStag + i
                println "Using BBSim Service config ${bbsimCfg['servicesConfig']}"
                writeYaml file: "$WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml", data: bbsimCfg
            } else {
                // NOTE if it's DT just copy the file over
                sh """
          cp $WORKSPACE/voltha-helm-charts/examples/${cfg.workflow}-values.yaml $WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml
          """
            }
        }
        
        sh(label  : "HELM: Create namespace=${cfg.volthaNamespace} bbsim${i}",
           script :  """
        helm upgrade --install --create-namespace -n ${cfg.volthaNamespace} bbsim${i} ${bbsimChart} \
        --set olt_id="${cfg.stackId}${i}" \
        -f $WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml \
        ${cfg.extraHelmFlags}
      """)
    }
    
    sh(label  : "Wait for VOLTHA Stack ${cfg.stackName} to start",
       script : """
#        set +x

        # [joey]: debug
        kubectl get pods -n ${cfg.volthaNamespace} -l app.kubernetes.io/part-of=voltha --no-headers

        voltha=\$(kubectl get pods -n ${cfg.volthaNamespace} -l app.kubernetes.io/part-of=voltha --no-headers | grep "0/" | wc -l)
        while [[ \$voltha != 0 ]]; do
          sleep 5
          voltha=\$(kubectl get pods -n ${cfg.volthaNamespace} -l app.kubernetes.io/part-of=voltha --no-headers | grep "0/" | wc -l)
        done
    """)
    
    waitForAdapters(cfg)

    // also make sure that the ONOS config is loaded
    // NOTE that this is only required for VOLTHA-2.8
    println "Wait for ONOS Config loader to complete"

    // Wait until the pod completed, meaning ONOS fully deployed
    sh(label   : '"Wait for ONOS Config loader to fully deploy',
        script : """
        # set +x#        # Noisy when commented (default: uncommented)

cat <<EOM

** -----------------------------------------------------------------------
** Wait for ONOS Config loader to fully deploy
**   IAM: vars/volthaStackDeploy.groovy
**   DBG: Polling loop initial kubectl get pods call
** -----------------------------------------------------------------------
** 17:06:07  Cancelling nested steps due to timeout
** 17:06:07  Sending interrupt signal to process
** 17:06:09  /w/workspace/verify_voltha-openolt-adapter_sanity-test-voltha-2.12@tmp/durable-18af2649/script.sh: line 7: 29716 Terminated              sleep 5
** 17:06:09  script returned exit code 143
** -----------------------------------------------------------------------
EOM
        kubectl get pods -l app=onos-config-loader -n ${cfg.infraNamespace} --no-headers --field-selector=status.phase=Running

        config=\$(kubectl get pods -l app=onos-config-loader -n ${cfg.infraNamespace} --no-headers --field-selector=status.phase=Running | grep "0/" | wc -l)
        while [[ \$config != 0 ]]; do
          sleep 5
          config=\$(kubectl get pods -l app=onos-config-loader -n ${cfg.infraNamespace} --no-headers --field-selector=status.phase=Running | grep "0/" | wc -l)
        done
    """)

    leave('process')
    
    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(Map config=[:])
{
    try
    {   
        enter('main')
        process(config)
    }
    catch (Exception err) {  // groovylint-disable-line CatchException
        ans = false
        println("** volthaStackDeploy.groovy: EXCEPTION ${err}")
        throw err
    }
    finally
    {
        leave('main')
    }
    return
}

// [EOF]
