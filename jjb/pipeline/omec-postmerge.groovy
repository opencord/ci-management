// Copyright 2020-present Open Networking Foundation
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

// omec-postmerge.groovy
// Combines docker-publish and deploy-staging pipelines into one job that can be triggered by a GitHub PR merge

pipeline {

  agent {
    label "${params.buildNode}"
  }

  stages {

    stage('Publish') {
      steps {
        build job: "docker-publish-github_$repoName", parameters: [
              string(name: 'gitUrl', value: "${repoUrl}"),
              string(name: 'gitRef', value: "${branchName}"),
              string(name: 'branchName', value: "${branchName}-${commitHash}"),
              string(name: 'projectName', value: "${repoName}"),
            ]
      }
    }
  }
  post {
    failure {
      step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${params.maintainers}", sendToIndividuals: false])
    }
  }
}
