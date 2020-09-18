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
          build_job_name = "docker-publish-github_$repoName"
          if ("${repoName}" == "spgw"){
            build_job_name = "aether-member-only-jobs/" + build_job_name
          }
          abbreviated_commit_hash = commitHash.substring(0, 7)
          tags_to_build = [ "${branchName}-latest",
                            "${branchName}-${abbreviated_commit_hash}" ]
          env_vars = [ "", "" ]
          if ("${repoName}" == "upf-epc"){
            tags_to_build += [ "${branchName}-latest-ivybridge",
                               "${branchName}-${abbreviated_commit_hash}-ivybridge" ]
            env_vars = [ "CPU=haswell", "CPU=haswell", "CPU=ivybridge", "CPU=ivybridge" ]
          }
          builds = [:]
          for(i = 0; i < tags_to_build.size(); i += 1) {
            builds[i] = {
              build job: "${build_job_name}", parameters: [
                    string(name: 'gitUrl', value: "${repoUrl}"),
                    string(name: 'gitRef', value: "${branchName}"),
                    string(name: 'branchName', value: tags_to_build[i]),
                    string(name: 'projectName', value: "${repoName}"),
                    string(name: 'extraEnvironmentVars', value: env_vars[i]),
              ]
            }
          }
          parallel builds
        }
      }
    }

    stage ("Prepare OMEC deployment"){
      steps {
        script {
          hssdb_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/c3po-hssdb/tags/' | jq '.results[] | select(.name | test("${c3poBranchName}-[0-9a-z]{7}\$")).name' | head -1 | tr -d \\\""""
          hss_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/c3po-hss/tags/' | jq '.results[] | select(.name | test("${c3poBranchName}-[0-9a-z]{7}\$")).name' | head -1 | tr -d \\\""""
          mme_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/nucleus/tags/' | jq '.results[] | select(.name | test("${nucleusBranchName}-[0-9a-z]{7}\$")).name' | head -1 | tr -d \\\""""
          spgwc_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/spgw/tags/' | jq '.results[] | select(.name | test("${spgwBranchName}-[0-9a-z]{7}\$")).name' | head -1 | tr -d \\\""""
          bess_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/upf-epc-bess/tags/' | jq '.results[] | select(.name | test("${upfBranchName}-[0-9a-z]{7}\$")).name' | head -1 | tr -d \\\""""
          zmqiface_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/upf-epc-cpiface/tags/' | jq '.results[] | select(.name | test("${upfBranchName}-[0-9a-z]{7}\$")).name' | head -1 | tr -d \\\""""
          pfcpiface_tag = sh returnStdout: true, script: """curl -s 'https://registry.hub.docker.com/v2/repositories/omecproject/upf-epc-pfcpiface/tags/' | jq '.results[] | select(.name | test("${upfBranchName}-[0-9a-z]{7}\$")).name' | head -1 | tr -d \\\""""

          hssdb_image = "omecproject/c3po-hssdb:"+hssdb_tag
          hss_image = "omecproject/c3po-hss:"+hss_tag
          mme_image = "omecproject/nucleus:"+mme_tag
          spgwc_image = "omecproject/spgw:"+spgwc_tag
          bess_image = "omecproject/upf-epc-bess:"+bess_tag
          zmqiface_image = "omecproject/upf-epc-cpiface:"+zmqiface_tag
          pfcpiface_image = "omecproject/upf-epc-pfcpiface:"+pfcpiface_tag

          switch("${params.repoName}") {
          case "c3po":
            hssdb_image = "${params.registry}/c3po-hssdb:${branchName}-${abbreviated_commit_hash}"
            hss_image = "${params.registry}/c3po-hss:${branchName}-${abbreviated_commit_hash}"
            break
          case "spgw":
            spgwc_image = "${params.registry}/spgw:${branchName}-${abbreviated_commit_hash}"
            break
          case "Nucleus":
            mme_image = "${params.registry}/nucleus:${branchName}-${abbreviated_commit_hash}"
            break
          case "upf-epc":
            bess_image = "${params.registry}/upf-epc-bess:${branchName}-${abbreviated_commit_hash}"
            zmqiface_image = "${params.registry}/upf-epc-cpiface:${branchName}-${abbreviated_commit_hash}"
            pfcpiface_image = "${params.registry}/upf-epc-pfcpiface:${branchName}-${abbreviated_commit_hash}"
            break
          }
          echo "Using hssdb image: ${hssdb_image}"
          echo "Using hss image: ${hss_image}"
          echo "Using mme image: ${mme_image}"
          echo "Using spgwc image: ${spgwc_image}"
          echo "Using bess image: ${bess_image}"
          echo "Using zmqiface image: ${zmqiface_image}"
          echo "Using pfcpiface image: ${pfcpiface_image}"
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
                  string(name: 'bessImage', value: "${bess_image.trim()}"),
                  string(name: 'zmqifaceImage', value: "${zmqiface_image.trim()}"),
                  string(name: 'pfcpifaceImage', value: "${pfcpiface_image.trim()}"),
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
