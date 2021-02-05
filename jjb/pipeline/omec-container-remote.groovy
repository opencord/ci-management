// SPDX-FileCopyrightText: 2021 Open Networking Foundation <info@opennetworking.org>
// SPDX-License-Identifier: LicenseRef-ONF-Member-Only-1.0

pipeline {

  agent {
    label "${params.buildNode}"
  }

  options {
    lock(resource: "${params.pod}")
  }

  stages {
    stage ("Environment Cleanup"){
      steps {
        step([$class: 'WsCleanup'])
      }
    }

    stage ("Trigger Remote Test Job"){
      steps {
        withCredentials([string(credentialsId: 'aether-jenkins-remote-trigger-token-omec', variable: 'token')]) {
          script {
            def handle = triggerRemoteJob job: "${params.project}_premerge_${params.pod}",
                         parameters: """
                                     ghprbTargetBranch=${params.ghprbTargetBranch}
                                     ghprbPullId=${params.ghprbPullId}
                                     ghprbActualCommit=${params.ghprbActualCommit}
                                     """,
                         remoteJenkinsName: "${remoteJenkinsName}",
                         token: "${token}"
            echo 'Remote Status: ' + handle.getBuildStatus().toString()
          }
        }
      }
    }
  }
  post {
    always {
      // Copy artifacts from the remote job dir (make sure both jobs run on the same node)
      sh """
      cp -r ../${params.project}_premerge_${params.pod}/* ./
      """
      archiveArtifacts artifacts: "omec/*/*", allowEmptyArchive: true
      archiveArtifacts artifacts: "ng40/*/*", allowEmptyArchive: true
    }
  }
}
