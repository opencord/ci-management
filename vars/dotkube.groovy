#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Copyright 2024 Open Networking Foundation Contributors
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
// SPDX-FileCopyrightText: 2024 Open Networking Foundation Contributors
// SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------
// Intent: Helper script for kubernetes debugging
// Called by: jjb/pipeline/voltha/bbsim-tests.groovy
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
// Intent: Terminate a process by name.
// -----------------------------------------------------------------------
// Note: Due to an exception casting GString to java.lang.string:
//   - Used for parameterized construction of a command line with args
//   - Passed to jenkins sh("${cmd}")
//   - Command line invoked is currently hardcoded.
// -----------------------------------------------------------------------
Boolean process(String proc, Map args) {
    Boolean ans = true
    String  iam = getIam('process')

    // clusterName: kind-ci
    // config=kind-{clusterName}
    // -------------------------
    // loader.go:223] Config not found: /home/jenkins/.kube/kind-kind-ci
    sh(
        label  : '[DEBUG] ls ~/.kube',
        script : """
echo -e "\n** /bin/ls -ld ~/.kube"
/bin/ls -ld ~/.kube

echo -e "\n** /bin/ls -ld: recursive"
find ~/.kube/. -print0 | xargs -0 /bin/ls -ld
""")

    return(ans)
}

// -----------------------------------------------------------------------
// Intent: Display debug info about .kube/*
// -----------------------------------------------------------------------
// Usage: dotkube([debug:False])
// -----------------------------------------------------------------------
Boolean call\
(
    Map config=[:]    // Args passed to callback function
) {
    // Boolean debug = config.debug ?: false

    String iam = getIam('main')
    Boolean ans = true

    try {
        enter(iam)
        process()
    }

    // [DEBUG] function is non-fatal
    catch (Exception err) { // groovylint-disable-line CatchException
        // ans = false
        println("** ${iam}: EXCEPTION WARNING ${err}")
        // println("** ${iam}: EXCEPTION ${err}")
        // throw err // non-fatal, no need to croak on find/ls errs
    }

    finally {
        leave(iam)
    }

    return(ans)
}

// [EOF]
