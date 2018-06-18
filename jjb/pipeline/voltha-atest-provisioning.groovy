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

    stage ('Bring up voltha dev vm') {
      steps {
        sh '''
        pushd $WORKSPACE/cord/incubator/voltha
        vagrant up voltha
        popd
        '''
        }
      }
    stage ('Remove the pre-created venv-linux') {
      steps {
        sh 'vagrant ssh -c "rm -rf $WORKSPACE/cord/incubator/voltha/venv-linux"'
        }
      }

    stage ('Build voltha and onos') {
      steps {
        sh 'vagrant ssh -c "cd $WORKSPACE/cord/incubator/voltha && source env.sh && make fetch-jenkins && make jenkins && make onos" voltha' }
        }

    stage ('Start Provisioning Test') {
      steps {
        println 'Start Provisioning Test'
        sh 'vagrant ssh -c "cd $WORKSPACE/cord/incubator/voltha/tests && pwd" voltha' }
      }
    }
}
