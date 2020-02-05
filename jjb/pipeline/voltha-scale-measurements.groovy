/* voltha-scale-measurements pipeline */
pipeline {
  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  stages {
    stage('checkout') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "https://github.com/ciena/kind-voltha.git", ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "kind-voltha"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        script {
          git_tags = sh(script:"cd kind-voltha; git tag -l --points-at HEAD", returnStdout: true).trim()
        }
      }
    }
    stage('start') {
      steps {
        sh """
          #!/usr/bin/env bash
          set -euo pipefail
          echo "DO SOMETHING"
          """
      }
    }
  }
  post {
    cleanup {
      sh '''
        #!/usr/bin/env bash
        set -euo pipefail
        cd $WORKSPACE/kind-voltha
        WAIT_ON_DOWN=y ./voltha down
        cd $WORKSPACE/
        rm -rf kind-voltha/ || true
      '''
    }
  }
}