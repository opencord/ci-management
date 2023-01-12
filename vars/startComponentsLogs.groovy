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
// Intent:
// check if kail is installed, if not installs it
// and then uses it to collect logs on specified containers
//
// appsToLog is a list of kubernetes labels used by kail to get the logs
// the generated log file is named with the string after =
// for example app=bbsim will generate a file called bbsim.log
//
// to archive the logs use: archiveArtifacts artifacts: '${logsDir}/*.log'
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getIam(String func)
{
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/startComponentLogs.groovy'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(Map config) {

    String iam = getIam('main')
    println("** ${iam}: ENTER")

    def tagPrefix = "jenkins"

    def defaultConfig = [
        appsToLog: [
            'app=onos-classic',
            'app=adapter-open-onu',
            'app=adapter-open-olt',
            'app=rw-core',
            'app=ofagent',
            'app=bbsim',
            'app=radius',
            'app=bbsim-sadis-server',
            'app=onos-config-loader',
        ],
        logsDir: "$WORKSPACE/logs"
    ]

    if (!config) {
        config = [:]
    }

    def cfg = defaultConfig + config

    // check if kail is installed and if not installs it
    sh("""make -C "$WORKSPACE/voltha-system-tests" KAIL_PATH="$WORKSPACE/bin" kail""")

    // if logsDir does not exists dir() will create it
    dir(cfg.logsDir) {
        for(int i = 0;i<cfg.appsToLog.size();i++) {
            def label = cfg.appsToLog[i]
            def logFile = label.split('=')[1]
            def tag = "${tagPrefix}-kail-${logFile}"
            println "Starting logging process for label: ${label}"
            sh """
            _TAG=${tag} kail -l ${label} > ${cfg.logsDir}/${logFile}.log&
            """
        }
    }

    println("** ${iam}: LEAVE")
    return
}

// [EOF]
