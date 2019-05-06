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

// synopsys-check.groovy

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  options {
      timeout(30)
  }

  stages {

    stage ("Clean workspace") {
      steps {
        sh 'rm -rf *'
      }
    }

    stage ("Get repo list") {
      steps {
        script {
          def repos = sh(
              returnStdout: true,
              script: """
                #!/usr/bin/env bash
                set -eu -o pipefail

                if [ -z "${github_organization}" ]
                then
                  # no github org set, assume gerrit server
                  curl "${git_server_url}/projects/?pp=0" | python -c 'import json,sys; ij=sys.stdin.readlines(); obj=json.loads(ij[1]); print(",".join(obj.keys()))'
                else
                  # github org set, assume github organization
                  curl -sS "https://api.github.com/orgs/${github_organization}/repos" | python -c 'import json,sys;obj=json.load(sys.stdin); print ",".join(map(lambda item: item["name"], obj))'
                fi
              """
              ).split(",")
        }
      }
    }

    stage ("Checkout repos") {
      steps {
        script {
          repos.each { gitRepo ->
            sh "echo Checking out: ${gitRepo}"
            checkout(
                [
                $class: 'GitSCM',
                userRemoteConfigs: [[
                url: "${params.git_server_url}/${gitRepo}/",
                name: "${branch}",
                ]],
                extensions: [
                [$class: 'RelativeTargetDirectory', relativeTargetDir: "${gitRepo}"],
                [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
                ],
                ])
          }
        }
      }
    }

    stage ("Synopsys Detect") {
      steps {
        script {
          repos.each { gitRepo ->
            sh "echo Running Synopsys Detect on: ${gitRepo}"
            synopsys_detect("--detect.source.path=${gitRepo} --detect.project.name=${blackduck_project} --detect.project.version.name=${branch} --detect.blackduck.signature.scanner.snippet.mode=true --detect.tools=ALL --detect.cleanup=false")
          }
        }
      }
    }

    stage ("Save logs") {
      steps {
        sh returnStdout: true, script: """
          echo COPYING LOGS
          mkdir -p bd_logs
          cp -r /home/jenkins/blackduck/runs/* bd_logs
          ls -l bd_logs/*/*
          """
        archiveArtifacts artifacts:'bd_logs/**/*.*'
      }
    }
  }
}
