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
            image "reuse-verify:latest"
            label "${params.buildNode}"
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
            steps {
                checkout([
                    $class: 'GitSCM',
                    userRemoteConfigs: [[ url: "https://github.com/${params.ghprbGhRepository}", refspec: "+refs/pull/${params.ghprbPullId}/merge" ]],
                    ],
                )
            }
        }

        stage("Run REUSE Linter"){
            steps {
                catchError{
                    sh  """
                        #!/usr/bin/env bash

                        git checkout FETCH_HEAD
                        reuse lint
                        """
                }
            }
        }
    }
}
