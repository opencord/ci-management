/* seba-in-a-box build+test */

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }

  options {
      timeout(time: 90, unit: 'MINUTES')
  }

  environment {
      VOLTHA_LOG_LEVEL="DEBUG"
      TYPE="minimal"
      WITH_RADIUS="y"
      WITH_BBSIM="y"
      INSTALL_ONOS_APPS="y"
      CONFIG_SADIS="y"
      FANCY=0
      WITH_SIM_ADAPTERS="n"
  }

  stages {

    stage('Create K8s Cluster') {
      steps {
        sh """
           git clone https:/gerrit.opencord.org/kind-voltha
           cd kind-voltha/
           DEPLOY_K8S=y JUST_K8S=y ./voltha up
           """
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh '''
           cd $WORKSPACE/kind-voltha/
           echo \$HELM_FLAG
           ./voltha up
           '''
      }
    }


  }

    post {
        always {
          sh '''
             WAIT_ON_DOWN=y ./voltha down
             cd $WORKSPACE/
             rm -rf kind-voltha/ voltha/ || true
             '''
        }
        failure {
          sh '''
             '''
        }
    }
}
