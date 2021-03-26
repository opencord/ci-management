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

def appRepo = '${appRepo}'
def appName = '${appName}'
def apiVersion = '${apiVersion}'
def nextApiVersion = '${nextApiVersion}'
def version = '${version}'
def nextVersion = '${nextVersion}'
def branch = '${branch}'
def jdkDistro = '${jdkDistro}'

// This pipeline updates the <version> tag in the root pom.xml for the
// given app repo, and pushes two new Gerrit changes:
//   1) With version the given ${version} (e.g., 1.0.0)
//   2) With ${nextVersion}-SNAPSHOT (e.g., 1.1.0-SNAPSHOT)
//
// Users must manually approve and merge these changes on Gerrit. Once merged,
// it's up to the maven-publish and version-tag jobs to complete the release by
// uploading artifacts to Sonatype and creating Git tags.

def changeVersion(def newVersion, def newApiVersion) {
  // Update the top-level <version> tag in the root pom.xml.
  sh 'mvn versions:set -DnewVersion=' + newVersion + ' versions:commit'
  sh 'mvn versions:set-property -Dproperty=' + appName + '.api.version -DnewVersion=' + newApiVersion + ' -DallowSnapshots=true versions:commit'
}

// TODO: use the declarative pipeline syntax, like all other groovy files.
//  This implementation is based on the legacy cord-onos-publisher/Jenkinsfile.release
node ('ubuntu16.04-basebuild-1c-2g') {

  sh 'echo Releasing ' + appRepo + ' repository on ' + branch + ' branch'
  sh 'echo Releasing version ' + version + ' with API version ' + apiVersion + '"' + ' and starting ' + nextVersion + '-SNAPSHOT with API version ' + apiVersion + '-SNAPSHOT'"'

  // Set the JDK version
  sh 'echo Using JDK distribution: ' + jdkDistro
  sh 'sudo update-java-alternatives --set ' + jdkDistro
  sh 'echo Java Version:'
  sh 'java -version'

  def userId = wrap([$class: 'BuildUser']) {
    return env.BUILD_USER_ID
  }

  stage ('Configure system') {
    echo "Job triggered by " + userId
    // FIXME: supply Jenkins-owned known_hosts file via config_file_provider
    //  https://jenkins.io/doc/pipeline/steps/config-file-provider/
    sh 'ssh-keyscan -H -t rsa -p 29418 gerrit.opencord.org >> ~/.ssh/known_hosts'

    sh 'git config --global user.name "Jenkins"'
    sh 'git config --global user.email "do-not-reply@opennetworking.org"'

    // GPG key used to sign maven artifacts
    withCredentials([file(credentialsId: 'gpg-creds-maven', variable: 'GPUPG')]) {
      sh 'tar -xvf $GPUPG -C ~'
    }
  }

  stage ('Check out code') {
    cleanWs()

    sshagent (credentials: ['gerrit-jenkins-user']) {
      git branch: branch, url: 'ssh://jenkins@gerrit.opencord.org:29418/' + appRepo, credentialsId: 'gerrit-jenkins-user'

      sh 'gitdir=$(git rev-parse --git-dir); scp -p -P 29418 jenkins@gerrit.opencord.org:hooks/commit-msg ${gitdir}/hooks/'
    }
  }

  stage ('Move to release version') {
    changeVersion(version, apiVersion)
    sh 'git add -A && git commit -m "Release app version ' + version + ' with API version ' + apiVersion + '"'
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

  stage ('Push to Gerrit') {
    sshagent (credentials: ['gerrit-jenkins-user']) {
      sh 'git push origin HEAD:refs/for/' + branch
    }
  }

  stage ('Move to next SNAPSHOT version') {
    def snapshot = nextVersion + '-SNAPSHOT'
    def apiSnapshot = nextApiVersion + '-SNAPSHOT'
    changeVersion(snapshot, apiSnapshot)
    sh 'git add -A && git commit -m "Starting snapshot ' + snapshot + ' with API version ' + apiSnapshot + '"'
    sshagent (credentials: ['gerrit-jenkins-user']) {
      sh 'git push origin HEAD:refs/for/' + branch
    }
  }

  stage ('Finish') {
    sh 'echo "Release done!"'
    sh  'echo "Go to Gerrit and merge new changes"'
    sh  'echo "Go to http://oss.sonatype.org and release the artifacts (after the maven-publish job completes)"'
  }
}

