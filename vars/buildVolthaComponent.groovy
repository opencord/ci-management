def call(String project) {
  // project is the gerrit project name

  if (project != 'voltha-system-tests' &&
    project != 'voltha-helm-charts' &&
    project != '') {

    sh """
    make -C $WORKSPACE/${project} DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
    """
  } else {
    println "The project ${project} does not require to be built."
  }

}
