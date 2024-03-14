// Copyright 2020-2024 Open Networking Foundation Contributors
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

// omec-reuse-scan.groovy
// checks an omec-project repo against reuse in a docker container

pipeline {

    agent {
        docker {
            image "registry.aetherproject.org/ci/reuse-verify:latest"
            label "${params.buildNode}"
            registryUrl "https://registry.aetherproject.org/"
            registryCredentialsId "registry.aetherproject.org"
        }
    }

    options {
        timeout(15)
    }

    stages {

        stage ("Clean Workspace") {
            steps {
                sh 'rm -rf *'
            }
        }

        stage ("Checkout Pull Request") {
            when {
                expression {return params.ghprbPullId != ""}
            }
            steps {
                checkout([
                    $class: 'GitSCM',
                    userRemoteConfigs: [[ url: "https://github.com/${params.ghprbGhRepository}", refspec: "pull/${params.ghprbPullId}/head" ]],
                    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${params.project}"]],
                    ],
                )
            }
        }

        stage ("Checkout Repo (manual)") {
            when {
                expression {return params.ghprbPullId == ""}
            }
            steps {
                checkout([
                    $class: 'GitSCM',
                    userRemoteConfigs: [[ url: "https://github.com/${params.ghprbGhRepository}" ]],
                    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${params.project}"]],
                    ],
                )
            }
        }

        stage("Run REUSE Linter"){
            steps {
                sh  """
                    #!/usr/bin/env bash

                    cd ${params.project}
                    if [ ! -z ${params.ghprbPullId} ]
                    then
                      git checkout FETCH_HEAD
                    else
                      git checkout ${params.branch}
                    fi
                    git show
                    reuse lint
                    """
            }
        }
    }
}
