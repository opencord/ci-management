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

/* -----------------------------------------------------------------------
groovylint-disable NoDef, VariableTypeRequired
Reason: jenkins.sh() and groovy String do not play nicely together.
        cast(String) => java.lang.String not supported natively.
* -----------------------------------------------------------------------
*/

def call(List namespaces = ['default'], List excludes = ['docker-registry']) {
    String spaces = namespaces.join(', ')
    println("Tearing down charts in namespaces: ${spaces}.")

    def exc = excludes.join('|')
    for (int i = 0; i < namespaces.size(); i++) {
        def n = namespaces[i]
        sh(label  : "Tearing down chart in namespace ${n}",
           script : """
          set +x
          for hchart in \$(helm list --all -n ${n} -q | grep -E -v '${exc}');
          do
              echo "Purging chart: \${hchart}"
              helm delete -n ${n} "\${hchart}"
          done
""")
    }

    banner = "Waiting for pods to be removed from namespaces: ${spaces}"
    for (int i = 0; i < namespaces.size(); i++) {
        def n = namespaces[i]
        sh(label  : "Waiting for pod removal in namespace ${n}",
           script : """
        set +x
        PODS=\$(kubectl get pods -n ${n} --no-headers | wc -l)
        while [[ \$PODS != 0 ]]; do
          sleep 5
          PODS=\$(kubectl get pods -n ${n} --no-headers | wc -l)
        done
""")
    }
}

// EOF
