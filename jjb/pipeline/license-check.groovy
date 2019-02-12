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

repos = params.repos.split(",")

node ("${TestNodeName}") {
    timeout (100) {
        try {
            stage ("Cleanup") {
                sh returnStdout: true, script: "rm -rf *"
            }
            stage ("Add private keys") {
                withCredentials([sshUserPrivateKey(credentialsId: '315e1f56-7193-464e-8af1-97bf7b1ee541', keyFileVariable: 'KEY')]) {
                    sh returnStdout: true, script: """
                    chmod 600 $KEY && eval `ssh-agent -s` && ssh-add $KEY &&
                    ssh-keyscan github.com >> ~/.ssh/known_hosts
                    """
                }
            }
            for(int i=0; i < repos.size(); i++) {
                stage ("Check repos ${repos[i]}") {
                    checkRepo(repos[i])
                }
            }
            currentBuild.result = 'SUCCESS'
        } catch (err) {
            currentBuild.result = 'FAILURE'
            step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "${notificationEmail}", sendToIndividuals: false])
        }
        echo "RESULT: ${currentBuild.result}"
    }
}

def checkRepo(repo) {
        sh returnStdout: true, script: """
            git clone -b ${branch} git@github.com:omec-project/${repo}.git
        """
        hub_detect("--detect.source.path=${repo} --detect.project.name=${prefix}-${repo} --detect.project.version.name=${branch} --snippet-matching --full-snippet-scan")
}
