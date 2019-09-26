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

  // Set so that synopsys_detect will know where to run golang tools from
  environment {
    PATH = "$PATH:/usr/lib/go-1.12/bin:/usr/local/go/bin/:$WORKSPACE/go/bin"
    GOPATH = "$WORKSPACE/go"
  }

  options {
      timeout(60)
  }

  stages {

    stage ("Clean workspace") {
      steps {
        sh 'rm -rf *'
      }
    }

    stage('Checkout') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "${params.gitUrl}", ]],
          branches: [[ name: "${params.gitRef}", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "${params.projectName}"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])

        // Used later to set the branch/tag in the blackduck UI release
        script {
          git_tag_or_branch = sh(script:"cd $projectName; if [[ \$(git tag -l --points-at HEAD) ]]; then git tag -l --points-at HEAD; else echo ${branchName}; fi", returnStdout: true).trim()
        }
      }
    }

    stage ("Prepare") {
      steps {

        // change the path tested if for golang projects which expect to be found in GOPATH
        script {
          test_path = sh(script:"if [ -f \"$projectName/Gopkg.toml\" ] || [ -f \"$projectName/go.mod\" ] ; then echo $WORKSPACE/go/src/github.com/opencord/$projectName; else echo $projectName; fi", returnStdout: true).trim()
        }

        sh returnStdout: true, script: """
          if [ -f "$projectName/package.json" ]
          then
            echo "Found '$projectName/package.json', assuming a Node.js project, running npm install"
            pushd "$projectName"
            npm install
            popd
          elif [ -f "$projectName/Gopkg.toml" ]
          then
            echo "Found '$projectName/Gopkg.toml', assuming a golang project using dep"
            mkdir -p "\$GOPATH/src/github.com/opencord/"
            mv "$WORKSPACE/$projectName" "$test_path"
            pushd "$test_path"
            dep ensure
            popd
          elif [ -f "$projectName/go.mod" ]
          then
            echo "Found '$projectName/go.mod', assuming a golang project using go modules"
            mkdir -p "\$GOPATH/src/github.com/opencord/"
            mv "$WORKSPACE/$projectName" "$test_path"
            pushd "$test_path"
            make dep
            popd
          fi
        """
      }
    }

    stage ("Synopsys Detect") {
      steps {
        // catch any errors that occur so that logs can be saved in the next stage
        // docs: https://jenkins.io/doc/pipeline/steps/workflow-basic-steps/#catcherror-catch-error-and-set-build-result-to-failure
        catchError {
          sh "echo Running Synopsys Detect on: ${projectName}"

          // Plugin: https://github.com/jenkinsci/synopsys-detect-plugin
          // Documentation: https://synopsys.atlassian.net/wiki/spaces/INTDOCS/pages/62423113/Synopsys+Detect
          // also: https://community.synopsys.com/s/article/Integrations-Documentation-Synopsys-Detect-Properties-for-version-5-4-0
          // also: Help menu after logging into BlackDuck portal
          synopsys_detect("--detect.source.path=$test_path " + \
                          "--detect.project.name=${blackduck_project}_${projectName} " + \
                          "--detect.project.version.name=$git_tag_or_branch " + \
                          "--detect.blackduck.signature.scanner.snippet.matching=SNIPPET_MATCHING " + \
                          "--detect.blackduck.signature.scanner.upload.source.mode=true " + \
                          "--detect.blackduck.signature.scanner.exclusion.patterns=/vendor/ " + \
                          "--detect.policy.check.fail.on.severities=ALL,BLOCKER,CRITICAL,MAJOR,MINOR,TRIVIAL " + \
                          "--detect.report.timeout=3600 " + \
                          "--detect.tools=ALL " + \
                          "--detect.cleanup=false")
        }
      }
    }

    stage ("Save Logs") {
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
