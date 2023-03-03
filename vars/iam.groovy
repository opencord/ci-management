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
// % npm-groovy-lint vars/iam.groovy
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
String getIam(String func)
{
    // Cannot rely on a stack trace due to jenkins manipulation
    // Report who and where caller came from.
    String src = [
        'repo:ci-management',
        'vars',
        'iam',
    ].join('/')

    String iam = [src, func].join('::')
    return(iam)
}

// -----------------------------------------------------------------------
// Intent: Display future enhancement list.
// -----------------------------------------------------------------------
void todo()
{
    String iam = getIam('todo')

    println("""
[TODO: $iam]
 o Pass jenkins parameters so todo() function can be conditionally called.
 o Add call parameters to:
   - Specify {ENTER,LEAVE} strings for logging.
   - Set methods for caller to specify an alternate getIam() path.
""")

    return
}

// -----------------------------------------------------------------------
// Intent: Placeholder in case future enhancements are needed
// -----------------------------------------------------------------------
Boolean process(Map config)
{
    String iam = getIam('process')

    Boolean leave = false

    // Identify caller with a banner for logging
    if (config.containsKey('enter')) {
        println("** ${iam}: ENTER")
    }
    else if (config.containsKey('leave')) {
	leave = true
    }
    else
    {
        println("** ${iam}: HELLO")
    }
 
    // Display future enhancement list
    if (config.containsKey('todo')) {
        todo()
    }

    // Maintain a sane logging enclosure block
    if (leave)
    {
        println("** ${iam}: LEAVE")
    }
    
    return(true)
}

// -----------------------------------------------------------------------
// Intent: Debug method for jenkins jobs identifying caller for logging.
// -----------------------------------------------------------------------
// Given:
//   self    jenkins environment pointer 'this'
//   config  groovy closure used to pass key/value pairs into argv
//     enter    Display "** {iam} ENTER"
//     leave    Display "** {iam} LEAVE"
//     todo     Display future enhancement list
// -----------------------------------------------------------------------
// Usage:
//   o called from a jenkins {pipeline,stage,script} block.
//   o iam(this)
//     {
//         foo  = bar    // paramter foo is...
//         tans = fans
//     }
// -----------------------------------------------------------------------
Boolean call(def self, Map config)
{
    String iam = getIam('main')

    argv = argv ?: [:] // {ternary,elvis} operator
    println("** ${iam}: argv=${argv}")

    Boolean ranToCompletion = false
    try
    {
        // [WIP] type(self) needed to quiet lint complaint.
        // npm-groovy-lint:  def for method parameter type should not be used  NoDef
        print(" ** $iam: Type of self variable is =" + self.getClass())
        // if (! self instanceof jenkins_object) { throw }

        if (process(argv))
        {
            ranToCompletion = true
        }
    }
    /* groovylint-disable*/ /* yuck! */
    catch (Exception err)
    /* groovylint-enable */
    {
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
    finally
    {
        println("** ${iam}: LEAVE")
    }

    if (!ranToCompletion)
    {
        throw new Exception("ERROR ${iam}: Detected incomplete script run")
    }

    return(true)
}

// [EOF]

