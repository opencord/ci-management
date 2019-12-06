// Copyright 2019-present Open Networking Foundation
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

def app = '${app}'
def version = '${version}'
def nextVersion = '${nextVersion}'
def branch = '${branch}'
def jdkDistro = '${jdkDistro}'

def changeVersion(def newVersion) {
  // TODO any other versions we need to account for?
  sh 'mvn versions:set -DnewVersion=' + newVersion + ' versions:commit'
}

node ('ubuntu16.04-basebuild-1c-2g') {

  sh 'echo Releasing ' + app + ' repository on ' + branch + ' branch'
  sh 'echo Releasing version ' + version + ' and starting ' + nextVersion + '-SNAPSHOT'

  // Set the JDK version
  sh 'echo Using JDK distribution: ' + jdkDistro
  sh 'sudo update-java-alternatives --set ' + jdkDistro
  sh 'echo Java Version:'
  sh 'java -version'

  def userId = wrap([$class: 'BuildUser']) {
    return env.BUILD_USER_ID
  }

  stage ('Configure system') {
    echo "Release build triggered by " + userId

    sh 'ssh-keyscan -H -t rsa -p 29418 gerrit.opencord.org >> ~/.ssh/known_hosts'

    sh 'git config --global user.name "Jenkins"'
    sh 'git config --global user.email "do-not-reply@opencord.org"'

    // GPG key used to sign maven artifacts
    withCredentials([file(credentialsId: 'gpg-creds-maven', variable: 'GPUPG')]) {
      sh 'tar -xvf $GPUPG -C ~'
    }
  }

  stage ('Check out code') {
    cleanWs()

    sshagent (credentials: ['gerrit-jenkins-user']) {
      git branch: branch, url: 'ssh://jenkins@gerrit.opencord.org:29418/' + app, credentialsId: 'gerrit-jenkins-user'

      sh 'gitdir=$(git rev-parse --git-dir); scp -p -P 29418 jenkins@gerrit.opencord.org:hooks/commit-msg ${gitdir}/hooks/'
    }
  }

  stage ('Move to release version') {
    changeVersion(version)
    sh 'git add -A && git commit -m "Release version ' + version + '"'
  }

  stage ('Verify code') {
    def found = sh script:'egrep -R SNAPSHOT .', returnStatus:true

    if (found == 0) {
      timeout(time: 1, unit: 'HOURS') {
        metadata = input id: 'manual-verify',
            message: 'Found references to SNAPSHOT in the code. Are you sure you want to release?',
            submitter: userId
      }
    }
  }

  // This step is basically to test that everything still builds once the version has
  // been bumped up before we start pushing things publicly
  stage ('Build and Test') {
    sh 'mvn -Pci-verify clean test install'
  }

  stage ('Push to Gerrit') {
    sshagent (credentials: ['gerrit-jenkins-user']) {
      sh 'git push origin HEAD:refs/for/' + branch
    }
  }

  stage ('Wait for merge') {
    timeout(time: 1, unit: 'HOURS') {
      metadata = input id: 'release-build',
          message: 'Go to Gerrit and merge the release patch',
          submitter: userId
    }

  }

  stage ('Tag the release') {
    sh 'git tag -a ' + version + ' -m "Tagging version ' + version + '"'
    sshagent (credentials: ['gerrit-jenkins-user']) {
      sh 'git push origin ' + version
    }
  }

  stage ('Move to next SNAPSHOT version') {
    def snapshot = nextVersion + '-SNAPSHOT'
    changeVersion(snapshot)
    sh 'git add -A && git commit -m "Starting snapshot ' + snapshot + '"'
    sshagent (credentials: ['gerrit-jenkins-user']) {
      sh 'git push origin HEAD:refs/for/' + branch
    }
  }

  stage ('Finish') {
    sh 'echo "Release done!"'
    sh  'echo "Go to Gerrit and merge snapshot version bump"'
    sh  'echo "Go to http://oss.sonatype.org and release the artifacts (after the publish Job completes)"'
  }

}

