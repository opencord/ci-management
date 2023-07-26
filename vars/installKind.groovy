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
String getIam(String func)
{
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/installKind.groovy'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def process(Map args)
{
    String iam = getIam('process')
    Boolean ans = true

    println("** ${iam}: ENTER branch=${args.branch}")
    println("args = " + args)

    // go install sigs.k8s.io/kind@v0.18.0
    sh(
	script: './installKind.sh',
	returnStdout: true
    )

    println("** ${iam}: LEAVE")
    return(ans)
}

// -----------------------------------------------------------------------
// TODO: Support native syntax:   installKind() { debug:true }
// -----------------------------------------------------------------------
/*
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
 */

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(String branch)
{
    String iam = getIam('main')
    println("** ${iam}: ENTER")
    println("** ${iam}: Debug= is " + config.contains(debug))

    try
    {
	// Will be passed in eventually
	Map config = [debug :false, branch:branch]
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
