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
// stops all the kail processes created by startComponentsLog
// -----------------------------------------------------------------------

def call(Map config) {

    def defaultConfig = [
        logsDir: "$WORKSPACE/logs",
        compress: false, // wether to compress the logs in a tgz file
    ]

    if (!config) {
        config = [:]
    }

    def cfg = defaultConfig + config

    def tag = "jenkins-"
    println "Stopping all kail logging process"
    sh """
    P_IDS="\$(ps e -ww -A | grep "_TAG=jenkins-kail" | grep -v grep | awk '{print \$1}')"
    if [ -n "\$P_IDS" ]; then
        for P_ID in \$P_IDS; do
            kill -9 \$P_ID
        done
    fi
    """
    if (cfg.compress) {
        sh """
        pushd ${cfg.logsDir}
        tar czf ${cfg.logsDir}/combined.tgz *
        rm *.log
        popd
        """

    }
}

// [EOF]
