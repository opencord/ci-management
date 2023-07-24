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

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getIam(String func)
{
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/installKind.groovy'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def process(Map args) {

    String iam = getIam('process')
    println("** ${iam}: ENTER")

    // go install sigs.k8s.io/kind@v0.18.0
    sh(
        returnStdout: true,
        script: """#!/bin/bash

set -eu -o pipefail
umask 0

function error()
{
    echo "** ${FUNCNAME[1]} ERROR: $*"
    exit 1
}

dir="$WORKSPACE/bin"
cmd="$dir/kind"
if [ ! -f "$cmd" ]; then
    mkdir -p "$dir"
    pushd "$dir" || error "pushd $dir failed"
    curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.11.0/kind-linux-amd64
    chmod +x ./kind
    popd         || error "popd $dir failed"
fi

## Sanity check installed binary
echo
echo "Kind command verison: $("$cmd" --version)"

return
""")
    println("** ${iam}: LEAVE")
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
Boolean call\
    (
    // def self,  // jenkins env object for access to primitives like echo()
    Closure body // jenkins closure attached to the call iam() {closure}
    )
{
    Map config           = [:]    // propogate block parameters
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate        = config // make parameters visible down below
    body()

    String iam = getIam('main')
    println("** ${iam}: ENTER")
    println("** ${iam}: Debug= is " + config.contains(debug))

    try
    {
        process(config)
    }
    catch (Exception err)
    {
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
    finally
    {
        println("** ${iam}: LEAVE")
    }
    return
}

// [EOF]
