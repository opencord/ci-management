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
            manifestGroup: 'voltha', \
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
           if [ "${params.manifestBranch}" != "master" ]
           then
             VOLTHA_BUILD=docker DOCKER_CACHE_ARG=--no-cache TAG=${params.manifestBranch} make build
           else
             VOLTHA_BUILD=docker DOCKER_CACHE_ARG=--no-cache make build
           fi
           popd
           """
      }
    }

    stage('push'){
      steps {
        withDockerRegistry([credentialsId: 'docker-artifact-push-credentials', url: '']) {
          sh """
            #!/usr/bin/env bash

            pushd cord/incubator/voltha
            if [ "${params.manifestBranch}" != "master" ]
            then
              if [ "${params.releaseTag}" != "" ]
              then
                VOLTHA_BUILD=docker TAG=${params.manifestBranch} TARGET_REPOSITORY=voltha/ TARGET_TAG=${params.releaseTag} make push
              else
                VOLTHA_BUILD=docker TAG=${params.manifestBranch} TARGET_REPOSITORY=voltha/ TARGET_TAG=${params.manifestBranch} make push
              fi
            else
              VOLTHA_BUILD=docker TARGET_REPOSITORY=voltha/ make push
            fi
            popd
            """
        }
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
