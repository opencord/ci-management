#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Copyright 2024 Open Networking Foundation (ONF) and the ONF Contributors
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
    String src = 'vars/dotkube.groovy'
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
// Intent: Display debug info about .kube/*
// -----------------------------------------------------------------------
def call(Map config) {
    config ?: [:]
    // Boolean debug = config.debug ?: false

    String iam = getIam('main')

    try {
        enter(iam)

        // clusterName: kind-ci
        // config=kind-{clusterName}
        // -------------------------
        // loader.go:223] Config not found: /home/jenkins/.kube/kind-kind-ci
        stage('.kube/ debugging')
        {
            sh("""/bin/ls -ld ~.kube """)
            sh("""find ~/.kube -print0 | xargs -0 /bin/ls -ld""")
            // if (config['do-something']) {}
        }
    }
    // groovylint-disable-next-line
    catch (Exception err) {
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
    finally {
        leave(iam)
    }

    return
}

// [EOF]
