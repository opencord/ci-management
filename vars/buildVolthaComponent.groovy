def call(String project) {
  // project is the gerrit project name

  // these are project that are not required to be built
  def ignoredProjects = [
    '', // this is the case for a manual trigger on master, nothing to be built
    'voltha-system-tests',
    'voltha-helm-charts'
  ]

  // some projects have different make targets
  def Map customMakeTargets = [
    "voltctl": "release"
  ]

  def defaultMakeTarget = "docker-build"

  if (!ignoredProjects.contains(project)) {

    def makeTarget = customMakeTargets.get(project, defaultMakeTarget)

    println "Building ${project} with make target ${makeTarget}."

    sh """
    make -C $WORKSPACE/${project} DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest ${makeTarget}
    """
  } else {
    println "The project ${project} does not require to be built."
  }

}
