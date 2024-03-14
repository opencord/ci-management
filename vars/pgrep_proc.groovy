#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Copyright 2021-2024 Open Networking Foundation Contributors
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
    String src = 'vars/pgrep_proc.groovy'
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

    String cmd = [
        'pgrep',
        '--uid', '$(id -u)', // no stray signals
        '--list-full',
        '--full',  // hmmm: conditional use (?)
        "'${proc}",
    ]

    print("""
** -----------------------------------------------------------------------
** Running: $cmd
** -----------------------------------------------------------------------
""")
    sh(
        label  : 'pgrep_proc', // jenkins usability: label log entry 'step'
        script : ${cmd}.toString(),
    )
    return(ans)
}

// -----------------------------------------------------------------------
// Install: Jenkins/groovy callback for installing the kind command.
//    o Paramter branch is passed but not yet used.
//    o Installer should be release friendly and checkout a frozen version
// -----------------------------------------------------------------------
// groovylint-disable-next-line None, UnusedMethodParameter
Boolean call\
(
    String  proc,           // name of process or arguments to terminate
    Map     args=[:],
    Boolean filler = true     // Groovy, why special case list comma handling (?)
) {
    String iam = getIam('main')
    Boolean ans = true

    throw new Exception("UNUSED: Not yet implemented")
    
    println("** ${iam}: ENTER")

    // var = Gstring throws exception due to cast problem.
    // var = [ 'foo', 'bar' 'tans' ].join(' ').toString() fails
    throw new Exception("Not yet implemented")
    
    try {
        process(proc, args)
    }
    catch (Exception err) {  // groovylint-disable-line CatchException
        ans = false
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
    finally {
        println("** ${iam}: LEAVE")
    }

    return(ans)
}

// [SEE ALSO]
// -----------------------------------------------------------------------
//   o String cmd = [ ... ].join('') -- GString cannot cast to java.String
//   o https://stackoverflow.com/questions/60304068/artifactory-in-jenkins-pipeline-org-codehaus-groovy-runtime-gstringimpl-cannot
// -----------------------------------------------------------------------
// [TODO] - Combine pkill_proc and pgrep_proc
//    - Usage: do_proc(pkill=true, pgrep=true, args='proc-forward', cmd='kubectl'
//      o When kill == grep == true: display procs, terminate, recheck: fatal if procs detected
//      o cmd && args (or command containing args) (or list of patterns passed)
//        - pass arg --full to match entire command line.
// -----------------------------------------------------------------------
// [EOF]
