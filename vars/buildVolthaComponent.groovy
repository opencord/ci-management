#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Copyright 2021-2024 Open Networking Foundation (ONF) and the ONF Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// -----------------------------------------------------------------------

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

// [EOF]
