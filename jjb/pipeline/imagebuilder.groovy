/* imagebuilder pipeline */
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

    stage('imagebuilder'){
      steps {
        sh """
           #!/usr/bin/env bash

           mkdir "$WORKSPACE/ib_logs"
           ib_args=""

           if [ "${params.force}" = "true" ]; then
             ib_args+="--force "
           fi

           if [ "${params.build}" = "true" ]; then
             ib_args+="--build "
           fi

           pushd cord/automation-tools/developer
           ./imagebuilder.py -vv \${ib_args} -c docker_images.yml \
                             -a "$WORKSPACE/ib_actions.yml" \
                             -l "$WORKSPACE/ib_logs" \
                             -g "$WORKSPACE/ib_graph.dot"
           popd
           """
      }
    }

    stage('push'){
       steps {
         script {
          def ib_actions = readYaml( file:"$WORKSPACE/ib_actions.yml" )

          withDockerRegistry([credentialsId: 'docker-artifact-push-credentials']) {
            for(image in ib_actions.ib_built){
              for(tag in image.tags){
                push_tag = image.base + ":" + tag
                echo "Pushing image: " + push_tag
                docker.image(push_tag).push()
              }
            }
          }
        }
      }
    }
  }

  post {
    always {
      archiveArtifacts artifacts: 'ib_actions.yml, ib_graph.dot, ib_logs/*', fingerprint: true
      deleteDir()
    }
    failure {
      emailext (
        to: "${params.failureEmail}"
      )
    }
  }
}
