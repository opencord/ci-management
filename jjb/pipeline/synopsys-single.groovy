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

// synopsys-single.groovy
// checks a single repo against synopsys

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

    stage('checkout') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "${params.gitUrl}",
            name: "${params.gitRef}",
          ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "${params.projectName}"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        script {
          git_tag_or_branch = sh(script:"cd $projectName; if [[ \$(git tag -l --points-at HEAD) ]]; then git tag -l --points-at HEAD; else echo ${branchName}; fi", returnStdout: true).trim()
        }
      }
    }

    stage ("Synopsys Detect") {
      steps {
        sh "echo Running Synopsys Detect on: ${params.projectName}"
        synopsys_detect("--detect.source.path=${params.projectName} --detect.project.name=${blackduck_project}_${params.projectName} --detect.project.version.name=$git_tag_or_branch --detect.blackduck.signature.scanner.snippet.mode=true --detect.tools=ALL --detect.cleanup=false")
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
