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

def hssdb_tag = ""
def hss_tag = ""
def mme_tag = ""
def spgwc_tag = ""
def spgwu_tag = ""
def abbreviated_commit_hash = ""

pipeline {

  agent {
    label "${params.buildNode}"
  }

  stages {

    stage('Publish') {
      steps {
        script {
          abbreviated_commit_hash = commitHash.substring(0, 7)
        }
        build job: "docker-publish-github_$repoName", parameters: [
              string(name: 'gitUrl', value: "${repoUrl}"),
              string(name: 'gitRef', value: "${branchName}"),
              string(name: 'branchName', value: "${branchName}-${abbreviated_commit_hash}"),
              string(name: 'projectName', value: "${repoName}"),
            ]
      }
    }

    stage('Deploy') {
      steps {
        script {
          hssdb_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/c3po-hssdb/tags/' | jq '.results[] | select(.name | contains("${c3poBranchName}")).name' | head -1 | tr -d \\\""""
          hss_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/c3po-hss/tags/' | jq '.results[] | select(.name | contains("${c3poBranchName}")).name' | head -1 | tr -d \\\""""
          mme_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/openmme/tags/' | jq '.results[] | select(.name | contains("${openmmeBranchName}")).name' | head -1 | tr -d \\\""""
          spgwc_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/ngic-cp/tags/' | jq '.results[] | select(.name | contains("${ngicBranchName}")).name' | head -1 | tr -d \\\""""
          spgwu_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/ngic-dp/tags/' | jq '.results[] | select(.name | contains("${ngicBranchName}")).name' | head -1 | tr -d \\\""""
          switch("${params.repoName}") {
          case "c3po":
            hssdb_tag = "${branchName}-${abbreviated_commit_hash}"
            hss_tag = "${branchName}-${abbreviated_commit_hash}"
            break
          case "ngic-rtc":
            spgwc_tag = "${branchName}-${abbreviated_commit_hash}"
            spgwu_tag = "${branchName}-${abbreviated_commit_hash}"
            break
          case "openmme":
            mme_tag = "${branchName}-${abbreviated_commit_hash}"
            break
          }
        }
        build job: "omec-deploy-staging", parameters: [
              string(name: 'hssdb_tag', value: "${hssdb_tag}"),
              string(name: 'mme_tag', value: "${hss_tag}"),
              string(name: 'hss_tag', value: "${mme_tag}"),
              string(name: 'spgwc_tag', value: "${spgwc_tag}"),
              string(name: 'spgwu_tag', value: "${spgwu_tag}"),
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
