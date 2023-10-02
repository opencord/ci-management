#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Copyright 2023 Open Networking Foundation (ONF) and the ONF Contributors
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
    String src = 'vars/pkill_port_forward.groovy'
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

    println("** ${iam}: args passed: ${args}")

    String cmd = [
        'pkill',
        '--uid', '$(id -u)', // no stray signals
        '--list-full',
        '--full',  // hmmm: conditional use (?)
        "'${proc}",
    ].join(' ')

    if (args['banner']) {
        print("""
** -----------------------------------------------------------------------
** Running: $cmd
** -----------------------------------------------------------------------
""")
    }

    if (args['show_procs']) {
        sh(
            label  : 'Display port forwarding (pre-pgrep-pkill)',
            script : """
pgrep --uid \$(id -u) --list-full --full 'port-forw' || true
""")
    }

    sh(
        label  : 'Kill port forwarding',
        // script : ${cmd}.toString(),  -> Exception
        script : """
echo -e "\n** vars/pkill_port_forward.groovy [DEBUG]: pgrep-pkill check"
if pgrep --uid \$(id -u) --list-full --full 'port-forw'; then
    pkill --uid \$(id -u) --echo --list-full --full 'port-forw'
fi
""")

    return(ans)
}

// -----------------------------------------------------------------------
// Install: Display a list of port-forwarding processes.
// -----------------------------------------------------------------------
// groovylint-disable-next-line None, UnusedMethodParameter
Boolean call\
(
    String  proc,             // name of process or arguments to terminate
    Map     args=[:],
    Boolean filler = true     // Groovy, why special case list comma handling (?)
) {
    Boolean ans = true

    try {
        enter('main')

        // Assign defaults
        ['banner', 'show_procs'].each { key ->
            if (!args.containsKey(key)) {
                args[key] = true
            }
        }

        process(proc, args)
    }
    catch (Exception err) {  // groovylint-disable-line CatchException
        ans = false
        String iam = getIam('main')
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
    finally {
        leave('main')
    }

    return(ans)
}

/* groovylint-disable */

// [SEE ALSO]
// -----------------------------------------------------------------------
//   o String cmd = [ ... ].join('') -- GString cannot cast to java.String
//   o https://stackoverflow.com/questions/60304068/artifactory-in-jenkins-pipeline-org-codehaus-groovy-runtime-gstringimpl-cannot
// -----------------------------------------------------------------------
// [TODO] - Combine pkill_proc and pkill_proc
//    - Usage: do_proc(pkill=true, pkill=true, args='proc-forward', cmd='kubectl'
//      o When kill == grep == true: display procs, terminate, recheck: fatal if procs detected
//      o cmd && args (or command containing args) (or list of patterns passed)
//        - pass arg --full to match entire command line.
// -----------------------------------------------------------------------
// [EOF]
