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
// Combines docker-publish, deploy and test pipelines into one job that can be triggered by a GitHub PR merge


pipeline {

  agent {
    label "${params.buildNode}"
  }

  stages {
    stage('Build and Publish') {
      steps {
        script {
          abbreviated_commit_hash = commitHash.substring(0, 7)
          tags_to_build = [ "${branchName}-latest",
                            "${branchName}-${abbreviated_commit_hash}"]
          tags_to_build.each { tag ->
            build job: "docker-publish-github_$repoName", parameters: [
                  string(name: 'gitUrl', value: "${repoUrl}"),
                  string(name: 'gitRef', value: "${branchName}"),
                  string(name: 'branchName', value: "${tag}"),
                  string(name: 'projectName', value: "${repoName}"),
                ]
          }
        }
      }
    }

    stage ("Prepare OMEC deployment"){
      steps {
        script {
          hssdb_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/c3po-hssdb/tags/' | jq '.results[] | select(.name | contains("${c3poBranchName}")).name' | head -1 | tr -d \\\""""
          hss_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/c3po-hss/tags/' | jq '.results[] | select(.name | contains("${c3poBranchName}")).name' | head -1 | tr -d \\\""""
          mme_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/nucleus/tags/' | jq '.results[] | select(.name | contains("${nucleusBranchName}")).name' | head -1 | tr -d \\\""""
          spgwc_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/ngic-cp/tags/' | jq '.results[] | select(.name | contains("${ngicBranchName}")).name' | head -1 | tr -d \\\""""
          spgwu_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/ngic-dp/tags/' | jq '.results[] | select(.name | contains("${ngicBranchName}")).name' | head -1 | tr -d \\\""""

          hssdb_image = "omecproject/c3po-hssdb:"+hssdb_tag
          hss_image = "omecproject/c3po-hss:"+hss_tag
          mme_image = "omecproject/nucleus:"+mme_tag
          spgwc_image = "omecproject/ngic-cp:"+spgwc_tag
          spgwu_image = "omecproject/ngic-dp:"+spgwu_tag

          switch("${params.repoName}") {
          case "c3po":
            hssdb_image = "${params.registry}/c3po-hssdb:${branchName}-${abbreviated_commit_hash}"
            hss_image = "${params.registry}/c3po-hss:${branchName}-${abbreviated_commit_hash}"
            break
          case "ngic-rtc":
            spgwc_image = "${params.registry}/ngic-cp:${branchName}-${abbreviated_commit_hash}"
            spgwu_image = "${params.registry}/ngic-dp:${branchName}-${abbreviated_commit_hash}"
            break
          case "Nucleus":
            mme_image = "${params.registry}/nucleus:${branchName}-${abbreviated_commit_hash}"
            break
          }
          echo "Using hssdb image: ${hssdb_image}"
          echo "Using hss image: ${hss_image}"
          echo "Using mme image: ${mme_image}"
          echo "Using spgwc image: ${spgwc_image}"
          echo "Using spgwu image: ${spgwu_image}"
        }
      }
    }

    stage ("Deploy and Test"){
      options {
        lock(resource: 'aether-dev-cluster')
      }

      stages {
        stage ("Deploy OMEC"){
          steps {
            build job: "omec_deploy_dev", parameters: [
                  string(name: 'hssdbImage', value: "${hssdb_image.trim()}"),
                  string(name: 'hssImage', value: "${hss_image.trim()}"),
                  string(name: 'mmeImage', value: "${mme_image.trim()}"),
                  string(name: 'spgwcImage', value: "${spgwc_image.trim()}"),
                  string(name: 'spgwuImage', value: "${spgwu_image.trim()}"),
            ]
          }
        }

        stage ("Run NG40 Tests"){
          steps {
            build job: "omec_ng40-test_dev"
          }
        }
      }
    }
  }
  post {
    failure {
      step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${params.maintainers}", sendToIndividuals: false])
    }
  }
}
