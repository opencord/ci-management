/* voltha-automated-build pipeline */

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

    stage('repo') {
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
        pushd incubator/voltha
        vagrant up voltha
        popd
        '''
        }
      }
    stage ('Remove the pre-created venv-linux') {
      steps {
        sh 'vagrant ssh -c "rm -rf /cord/incubator/voltha/venv-linux"'
        }
      }

    stage ('Build voltha') {
      steps {
        sh 'vagrant ssh -c "cd /cord/incubator/voltha && source env.sh && make fetch-jenkins && make jenkins" voltha' }
        }

    stage ('Bring up voltha containers') {
      steps {
        sh 'vagrant ssh -c "cd /cord/incubator/voltha && source env.sh && docker-compose -f compose/docker-compose-docutests.yml up -d" voltha' }
        }

    stage ('Run Integration Tests') {
      steps {
        sh 'vagrant ssh -c "cd /cord/incubator/voltha && source env.sh && make jenkins-test" voltha' }
        }

    }
}
