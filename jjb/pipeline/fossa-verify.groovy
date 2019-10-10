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

// fossa-verify.groovy
// checks a single repo against fossa

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  // Set so that fossa will know where to run golang tools from
  environment {
    PATH = "$PATH:/usr/lib/go-1.12/bin:/usr/local/go/bin/:$WORKSPACE/go/bin"
    GOPATH = "$WORKSPACE/go"
  }

  options {
      timeout("${params.build-timeout}")
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

        // Used later to set the branch/tag in
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

    stage ("fossa") {
      steps {
        // catch any errors that occur so that logs can be saved in the next stage
        // docs: https://jenkins.io/doc/pipeline/steps/workflow-basic-steps/#catcherror-catch-error-and-set-build-result-to-failure
        catchError {

          sh returnStdout: true, script: """
            echo "Creating debug output directory"
            mkdir -p "$WORKSPACE/debug"

            echo "Download/install fossa tool"
            curl -H 'Cache-Control: no-cache' -O https://raw.githubusercontent.com/fossas/fossa-cli/master/install.sh
            mkdir -p "$WORKSPACE/go/bin"
            bash install.sh -b "$WORKSPACE/go/bin"

            echo "Fossa version:"
            fossa -v

            echo "Testing project: $projectName located in $test_path"
            pushd "$test_path"

            echo "Fossa init"
            fossa init --no-ansi --debug 2>"$WORKSPACE/debug/init_stderr.log"

            echo "Fossa analyze"
            fossa analyze --no-ansi --debug \
                          -T "$fossaProject"
                          -t "$projectName" \
                          -L "$GERRIT_CHANGE_URL" \
                          -b "$git_tag_or_branch" \
                          -r "$GERRIT_CHANGE_NUMBER.$GERRIT_PATCHSET_NUMBER" \
                          2>"$WORKSPACE/debug/analyze_stderr.log"

            echo "Fossa test results"
            fossa test --no-ansi --debug \
                          -T "$fossaProject"
                          -t "$projectName" \
                          -L "$GERRIT_CHANGE_URL" \
                          -b "$git_tag_or_branch" \
                          -r "$GERRIT_CHANGE_NUMBER.$GERRIT_PATCHSET_NUMBER" \
                          2>"$WORKSPACE/debug/test_stderr.log"

            popd
          """
        }
      }
    }

    stage ("Save Logs") {
      steps {
        sh returnStdout: true, script: """
            echo "Logs:"
            ls -l $WORKSPACE/debug
          """
        archiveArtifacts artifacts:'debug/*.log'
      }
    }
  }
}

