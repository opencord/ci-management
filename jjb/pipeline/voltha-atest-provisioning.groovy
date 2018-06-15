/* voltha-atest-provisioning pipeline */

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  stages {

    stage ('Start Provisioning Test') {
      steps {
        println 'Start Provisioning Test'
      }
    }
  }
}
