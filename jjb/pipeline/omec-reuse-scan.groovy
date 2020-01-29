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
            image "fsfe/reuse:0.8.0"
            label "${params.executorNode}"
            args "-u root -it --entrypoint="
        }
    }

    options {
        timeout(15)
    }

    stages {

        stage("Clean Workspace"){
            steps{
                sh "rm -rf *"
            }
        }

        stage ("Checkout Pull Request") {
            steps {
                echo "GITHUB_PR_NUMBER: ${GITHUB_PR_NUMBER}"
                sh """
                  git clone https://github.com/omec-project/${params.project}
                  cd ${params.project}
                  git fetch origin +refs/pull/${GITHUB_PR_NUMBER}/merge
                  git checkout FETCH_HEAD
                """
            }
        }

        stage("Run REUSE Linter"){
            steps {
                catchError{
                    sh """
                        cd ${params.project}
                        reuse lint
                        """
                }
            }
        }
    }

    post {
        // send success status to GitHub if stages succeed
        success {
            setGitHubPullRequestStatus state: 'SUCCESS'
        }

        // send failure status to GitHub if a stage fails
        failure {
            setGitHubPullRequestStatus state: 'FAILURE'
        }
    }
}
