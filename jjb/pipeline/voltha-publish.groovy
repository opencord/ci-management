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
          set -x

          pushd cord/incubator/voltha
          if [ "${params.manifestBranch}" == "master" ]
          then
            tag="latest"
          else
            tag="${params.manifestBranch}"
          fi
          VOLTHA_BUILD=docker DOCKER_CACHE_ARG=--no-cache TAG=${tag} make build
          popd
          """
      }
    }

    stage('push'){
      steps {
        withDockerRegistry([credentialsId: 'docker-artifact-push-credentials', url: '']) {
          sh """
            #!/usr/bin/env bash
            set -x

            pushd cord/incubator/voltha
            if [ "${params.manifestBranch}" == "master" ]
            then
              tag="latest"
            else
              tag="${params.manifestBranch}"
            fi

            if [ "${params.releaseTag}" != "" ]
            then
              VOLTHA_BUILD=docker TAG=${tag} TARGET_REPOSITORY=voltha/ TARGET_TAG=${params.releaseTag} make push
            else
              VOLTHA_BUILD=docker TAG=${tag} TARGET_REPOSITORY=voltha/ TARGET_TAG=${tag} make push
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
