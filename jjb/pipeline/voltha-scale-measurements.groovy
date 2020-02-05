/* voltha-scale-measurements pipeline */
pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  stages {

    stage('start') {
      steps {
        sh """
          #!/usr/bin/env bash

          echo "DO SOMETHING"
          """
      }
    }
  }
}
