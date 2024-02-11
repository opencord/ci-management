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
// Install the voltctl command by branch name "voltha-xx"
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
String getIam(String func) {
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/installKind.groovy'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
Boolean process() {
    String iam  = getIam('process')
    Boolean ans = true

    println("** ${iam}: ENTER")

    String cmd = [
        'make',
        '--no-print-directory',
        '-C', "$WORKSPACE/voltha-system-tests",
        "KIND_PATH=\"$WORKSPACE/bin\"",
        'install-command-kind',
    ].join(' ')

    println(" ** Running: ${cmd}")
    sh(
        label  : 'Install: kind', // jenkins usability: label log entry 'step'
        script : "${cmd}",
    )

    println("** ${iam}: LEAVE")
    return(ans)
}

// -----------------------------------------------------------------------
// Install: Jenkins/groovy callback for installing the kind command.
//    o Paramter branch is passed but not yet used.
//    o Installer should be release friendly and checkout a frozen version
// -----------------------------------------------------------------------
def call(String branch) {
    String iam = getIam('main')

    println("** ${iam}: ENTER branch=${branch}")
    println('** WARNING: branch= Not Yet Implemented')

    try {
        process()
    }
    catch (Exception err) {
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
    finally {
        println("** ${iam}: LEAVE")
    }
    return
}

// [EOF]
