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
// Install the voltctl command by branch name "voltha-xx"
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
String getIam(String func) {
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/pkill_proc.groovy'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
Boolean process(String proc, Map args) {
    Boolean ans = true
    String  iam = getIam('process')

    if (args.containsKey('debug')) {
        println("** $iam [DEBUG]: proc=[$proc], args=[$args]")
    }

    String cmdKill = [
        'pkill',
        '--uid', '$(id -u)', // no stray signals
        '--echo',
        '--full',  // hmmm: conditional use (?)
        "'${proc}",
    ].join(' ')

    /*
    String cmdKill = (args['command_only')
        ? "pkill --echo '${proc}'"
        : "pkill --echo --full '${proc}'"
     */

    String cmd = """if [[ \$(pgrep --count "${proc}") -gt 0 ]]; then ${cmdKill}; fi"""

    print("""
** -----------------------------------------------------------------------
** Running: $cmd
** -----------------------------------------------------------------------
""")

    sh(
        label  : 'pkill_proc', // jenkins usability: label log entry 'step'
        script : "${cmd}",
    )

    return(ans)
}

// -----------------------------------------------------------------------
// Install: Jenkins/groovy callback for installing the kind command.
//    o Paramter branch is passed but not yet used.
//    o Installer should be release friendly and checkout a frozen version
// -----------------------------------------------------------------------
// groovylint-disable-next-line None, UnusedMethodParameter
void call\
(
    String  proc,           // name of process or arguments to terminate
    Map     args=[:],
                            // Groovy, why special case list comma handling (?)
    Boolean filler=true     // groovylint-disable-line UnusedMethodParameter
) {
    
    String iam = getIam('main')
    Boolean ans = true

    println("** ${iam}: ENTER")

    try {
        // Limit process matching by default
        if (! mymap.containsKey('command_only')) {
            mymap['command_only'] = true
        }
        process(proc, args)
    }
    catch (Exception err) { // groovylint-disable-line CatchException
        iam = 'pkill_proc.groovy'
        ans = False
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
    finally {
        println("** ${iam}: LEAVE")
    }

    return(ans)
}

// -----------------------------------------------------------------------
// [TODO] - Combine pkill_proc and pgrep_proc
//    - Usage: do_proc(pkill=true, pgrep=true, args='proc-forward', cmd='kubectl'
//      o When kill == grep == true: display procs, terminate, recheck: fatal if procs detected
//      o cmd && args (or command containing args) (or list of patterns passed)
//        - pass arg --full to match entire command line.
// -----------------------------------------------------------------------
// [EOF]
