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
           git clone https://github.com/ciena/kind-voltha.git
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

    stage('Run E2E Test') {
      steps {
        sh '''
           cd kind-voltha/
           export KUBECONFIG="$(./bin/kind get kubeconfig-path --name="voltha-minimal")"
           export VOLTCONFIG="/home/jenkins/.volt/config-minimal"
           export DEVICE_ID=$(voltctl device create -t openolt -H 10.64.1.131:50060)
           voltctl device enable $DEVICE_ID
           i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
           until [ $i -eq 1 ]
           do
                echo $i
                sleep 2
                i=$(voltctl -c .volt/config-compose device list | grep -v OLT | grep ACTIVE | wc -l)
                done
           '''
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
