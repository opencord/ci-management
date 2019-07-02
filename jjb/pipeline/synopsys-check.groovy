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

  // Set so that synopsys_detect will know where to run golang tools from
  environment {
    PATH = "$PATH:/usr/lib/go-1.12/bin:/usr/local/go/bin/:$WORKSPACE/go/bin"
    GOPATH = "$WORKSPACE/go"
  }

  options {
      timeout(240)
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
          writeFile file: 'get_repo_list.py', text: """
#!/usr/bin/env python

import json
import os
import requests

if "github_organization" in os.environ:
    # this is a github org
    github_req = requests.get("https://api.github.com/orgs/%s/repos" %
                              os.environ["github_organization"])

    # pull out the "name" key out of each item
    repo_list = map(lambda item: item["name"], github_req.json())

else:
    # this is a gerrit server

    # fetch the list of projects
    gerrit_req = requests.get("%s/projects/?pp=0" %
                              os.environ["git_server_url"])
    # remove XSSI prefix
    # https://gerrit-review.googlesource.com/Documentation/rest-api.html#output
    gerrit_json = json.loads(gerrit_req.text.splitlines()[1])

    # remove repos which don't contain code
    repo_list = [repo for repo in gerrit_json.keys()
                 if repo not in ["All-Projects", "All-Users", "voltha-bal"]]

# sort and print
print(",".join(sorted(repo_list)))
"""

          /* this defines the variable globally - not ideal, but works - see:
          https://stackoverflow.com/questions/50571316/strange-variable-scoping-behavior-in-jenkinsfile
          */
          repos = sh(
            returnStdout: true,
            script: "python -u get_repo_list.py").trim().split(",")

          echo "repo list: ${repos}"
        }
      }
    }

    stage ("Checkout repos") {
      steps {
        script {
          repos.each { gitRepo ->
            sh "echo Checking out: ${gitRepo}"
            checkout(changelog: false, scm: [
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
            synopsys_detect("--detect.source.path=${gitRepo} " + \
                            "--detect.project.name=${blackduck_project}_${projectName} " + \
                            "--detect.project.version.name=$git_tag_or_branch " + \
                            "--detect.blackduck.signature.scanner.snippet.matching=SNIPPET_MATCHING " + \
                            "--detect.blackduck.signature.scanner.upload.source.mode=true " + \
                            "--detect.blackduck.signature.scanner.exclusion.patterns=/vendor/ " + \
                            "--detect.tools=ALL " + \
                            "--detect.cleanup=false")
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
