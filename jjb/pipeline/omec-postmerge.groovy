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

// Builds and publishes OMEC docker images. Triggered by GitHub PR merge.


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
          def builds = [:]
          for(i = 0; i < tags_to_build.size(); i += 1) {
            def tag_to_build = tags_to_build[i]
            def env_var = env_vars[i]
            builds["${build_job_name}${i}"] = {
              build job: "${build_job_name}", parameters: [
                    string(name: 'gitUrl', value: "${repoUrl}"),
                    string(name: 'gitRef', value: "${branchName}"),
                    string(name: 'branchName', value: tag_to_build),
                    string(name: 'projectName', value: "${repoName}"),
                    string(name: 'extraEnvironmentVars', value: env_var),
              ]
            }
          }
          parallel builds
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
