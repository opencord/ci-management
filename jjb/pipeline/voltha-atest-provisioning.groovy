/* voltha-atest-provisioning pipeline */

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  stages {

    stage ('Cleanup workspace') {
      steps {
        sh 'rm -rf ./build ./component ./incubator ./onos-apps ./orchestration ./test ./.repo'
      }
    }

    stage('voltha Repo') {
      steps {
        checkout(changelog: false, \
          poll: false,
          scm: [$class: 'RepoScm', \
            manifestRepositoryUrl: "${params.manifestUrl}", \
            manifestBranch: "${params.manifestBranch}", \
            currentBranch: true, \
            destinationDir: 'cord', \
            forceSync: true,
            resetFirst: true, \
            quiet: true, \
            jobs: 4, \
            showAllChanges: true] \
          )
      }
    }

    stage ('Build voltha and onos') {
      steps {
        sh '''
        cd $WORKSPACE/cord/incubator/voltha
        source env.sh
        make fetch
        make clean
        make build
        make onos
        '''
      }
    }

    stage ('Start Provisioning Test') {
      steps {
        println 'Start Provisioning Test'
      }
    }
  }
}
