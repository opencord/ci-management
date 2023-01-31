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

def call(List namespaces = ['default'], List excludes = ['docker-registry']) {

    println "Tearing down charts in namespaces: ${namespaces.join(', ')}."

    def exc = excludes.join("|")
    for(int i = 0;i<namespaces.size();i++) {
        def n = namespaces[i]
        sh """
          for hchart in \$(helm list --all -n ${n} -q | grep -E -v '${exc}');
          do
              echo "Purging chart: \${hchart}"
              helm delete -n ${n} "\${hchart}"
          done
        """
    }

    println "Waiting for pods to be removed from namespaces: ${namespaces.join(', ')}."
    for(int i = 0;i<namespaces.size();i++) {
        def n = namespaces[i]
        sh """
        set +x
        PODS=\$(kubectl get pods -n ${n} --no-headers | wc -l)
        while [[ \$PODS != 0 ]]; do
          sleep 5
          PODS=\$(kubectl get pods -n ${n} --no-headers | wc -l)
        done
        """
    }
}

// EOF
