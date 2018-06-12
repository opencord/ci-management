/* voltha-automated-build pipeline */

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  stages {

    stage ('Cleanup workspace') {
        sh 'rm -rf ./build ./component ./incubator ./onos-apps ./orchestration ./test ./.repo'
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

    dir ('incubator/voltha') {
        try {
            stage ('Bring up voltha dev vm') {
            sh 'vagrant up voltha' }

            stage ('Remove the pre-created venv-linux') {
            sh 'vagrant ssh -c "rm -rf /cord/incubator/voltha/venv-linux"' }

            stage ('Build voltha') {
            sh 'vagrant ssh -c "cd /cord/incubator/voltha && source env.sh && make fetch-jenkins && make jenkins" voltha' }

            stage ('Bring up voltha containers') {
            sh 'vagrant ssh -c "cd /cord/incubator/voltha && source env.sh && docker-compose -f compose/docker-compose-docutests.yml up -d" voltha' }

            stage ('Run Integration Tests') {
            sh 'vagrant ssh -c "cd /cord/incubator/voltha && source env.sh && make jenkins-test" voltha' }

            currentBuild.result = 'SUCCESS'
            slackSend channel: '#voltha', color: 'good', message: "${env.JOB_NAME} (${env.BUILD_NUMBER}) Build success.\n${env.BUILD_URL}"
        } catch (err) {
            currentBuild.result = 'FAILURE'
            slackSend channel: '#voltha', color: 'danger', message: ":dizzy_face: Build failed ${env.JOB_NAME} (${env.BUILD_NUMBER})\n${env.BUILD_URL}"
        } finally {
            sh 'vagrant destroy -f voltha'
        }
        echo "RESULT: ${currentBuild.result}"
      }
    }
}
