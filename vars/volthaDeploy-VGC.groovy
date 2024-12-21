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
// this keyword is dedicated to deploy a single VOLTHA stack with infra
// If you need to deploy different configurations you can use the
// volthaInfraDeploy and volthaStackDeploy DSL keywords
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
String getIam(String func) {
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/volthaDeploy.groovy'
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
// Intent: Perform volthaDeploy stuff
// -----------------------------------------------------------------------
void process(Map config)
{
    // note that I can't define this outside the function as there's no global scope in Groovy
    // [joey] A class method or library call can be used in place of globals, fqdn needed.
    def defaultConfig = [
        onosReplica: 1,
        atomixReplica: 1,
        kafkaReplica: 1,
        etcdReplica: 1,
        bbsimReplica: 1,
        infraNamespace   : 'infra',
        volthaNamespace  : 'voltha',
        stackName        : 'voltha',
        stackId: 1,
        workflow         : 'att',
        withMacLearning: false,
        withFttb: false,
        extraHelmFlags   : '',
        localCharts: false, // Local or upstream (?) (for local we assume they are stored in $WORKSPACE/voltha-helm-charts)
        dockerRegistry   : '', // Different registry for images eg: "mirror.registry.opennetworking.org"
        kubeconfig: null, // location of the kubernetes config file, if null we assume it's stored in the $KUBECONFIG environment variable
        withVolthaInfra: true,
        withVolthaStack: true,
    ]

    if (!config) {
        config = [:]
    }

    def cfg = defaultConfig + config

    if (cfg.dockerRegistry != '') {
        def registryFlags = " --set global.image_registry=${cfg.dockerRegistry}/ "
        registryFlags += " --set etcd.image.registry=${cfg.dockerRegistry} "
        registryFlags += " --set kafka.image.registry=${cfg.dockerRegistry} "
        registryFlags += " --set kafka.zookeper.image.registry=${cfg.dockerRegistry} "
        registryFlags += " --set onos-classic.image.repository=${cfg.dockerRegistry}/voltha/voltha-onos "
        registryFlags += " --set onos-classic.atomix.image.repository=${cfg.dockerRegistry}/atomix/atomix "
        registryFlags += " --set freeradius.images.radius.registry=${cfg.dockerRegistry}/ "

        // we want to always leave the user provided flags at the end, to override changes
        cfg.extraHelmFlags = registryFlags + " " + cfg.extraHelmFlags
    }

    // ---------------------
    banner("Updating helm repos")

    sh(label  : 'Configure helm repo',
       script : """
helm repo add onf https://charts.opencord.org
helm repo update
""")

    // ---------------------
    banner("Deploying VOLTHA with the following parameters: ${cfg}")

    if (cfg.withVolthaInfra) {
        volthaInfraDeploy(cfg)
    }

    if (cfg.withVolthaStack) {
        volthaStackDeploy_VGC(cfg)
    }

    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(Map config=[:]) { // Function return type(?)
    String iam = getIam('main')
    Boolean ans = true

    try {
        enter('main')
        process(config)
    }
    catch (Exception err) {  // groovylint-disable-line CatchException
        ans = false
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
    finally {
        leave('main')
    }

    return(ans)
}

// [EOF]
