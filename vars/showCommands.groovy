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
    String src = 'vars/showCommands'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
void run_cmd(String command) {
    String buffer = [
        "Running command: $command",
    ]

    println("** ${iam}: Running ${command} LEAVE")
    try {
        // Run command for output
        buffer = sh(
            script: command,
            returnStdout: true
        ).trim()

        // Reached if no exceptions thrown
        buffer += [
            '',
            'Ran to completion',
        ]
    }
    /*
    catch (Exception err)
    {
        // Note the exception w/o failing
        buffer += [
            '',
            "${iam}: EXCEPTION ${err}",
        ].join('\n')
    }
     */
    finally
    {
        println("** ${iam}: " + buffer.join(' '))
        println("** ${iam}: Running ${command} LEAVE")
    }
    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
void process(Map config) {
    String iam = getIam('process')

    println("${iam} config=${config}")

    // list.each{ } could be used here but keep it simple for now.
    println("** ${iam}: voltctl command path")
    run_cmd('which -a voltctl 2>&1')

    println("** ${iam}: voltctl command version")
    run_cmd('voltctl version')
    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
Boolean call(Map config) {
    String iam = getIam('main')

    println("** ${iam}: ENTER")

    config = config ?: [:]

    try {
        process(config)
    }
    /*
    catch (Exception err)
    {
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
*/
    finally
    {
        println("** ${iam}: LEAVE")
    }
    return(true)
}

// [EOF]
