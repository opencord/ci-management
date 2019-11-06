/* seba-in-a-box build+test */

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }

  options {
      timeout(time: 90, unit: 'MINUTES')
  }

  stages {

    stage('Repo') {
      steps {
        checkout(changelog: false, \
          poll: false,
          scm: [$class: 'RepoScm', \
            manifestRepositoryUrl: "${params.manifestUrl}", \
            manifestBranch: "${params.manifestBranch}", \
            currentBranch: true, \
            destinationDir: 'voltha', \
            forceSync: true,
            resetFirst: true, \
            quiet: true, \
            jobs: 4, \
            showAllChanges: true] \
          )
      }
    }


    stage('Create K8s Cluster') {
      steps {
        sh """
           git clone https://github.com/ciena/kind-voltha.git
           cd kind-voltha/
           DEPLOY_K8S=y JUST_K8S=y FANCY=0 ./voltha up
           """
      }
    }
    
    tage('Deploy Voltha') {
      steps {
        sh '''
           cd $WORKSPACE/kind-voltha/
           echo \$HELM_FLAG
           EXTRA_HELM_FLAGS=\$HELM_FLAG VOLTHA_LOG_LEVEL=DEBUG TYPE=minimal WITH_RADIUS=y WITH_BBSIM=y INSTALL_ONOS_APPS=y CONFIG_SADIS=y FANCY=0 WITH_SIM_ADAPTERS=n ./voltha up
           '''
      }
    }


  }

    post {
        always {
          sh '''
             FANCY=0 WAIT_ON_DOWN=y ./voltha down
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
