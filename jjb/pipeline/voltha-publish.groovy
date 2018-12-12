/* voltha-publish pipeline */
pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }
  stages {

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

    stage('build'){
      steps {
        sh """
           #!/usr/bin/env bash

           pushd cord/incubator/voltha
           VOLTHA_BUILD=docker TAG=${params.manifestBranch} make build

           popd
           """
      }
    }

    stage('push'){
      steps {
        sh """
           #!/usr/bin/env bash

           pushd cord/incubator/voltha
           VOLTHA_BUILD=docker TAG=${params.manifestBranch} TARGET_REPOSITORY=voltha/ TARGET_TAG=${params.manifestBranch} make push

           popd
           """
      }
    }
  }

  post {
    failure {
      emailext (
        subject: "$PROJECT_NAME - Build # $BUILD_NUMBER - $BUILD_STATUS",
        body: "Check console output at $BUILD_URL to view the results.",
        to: "${params.failureEmail}"
      )
    }
  }
}
