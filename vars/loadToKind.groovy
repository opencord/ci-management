#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Copyright 2021-2023 Open Networking Foundation (ONF) and the ONF Contributors
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
// loads all the images tagged as citest on a Kind cluster
// -----------------------------------------------------------------------

def call(Map config) {
  def defaultConfig = [
    name: "kind-ci"
  ]

  if (!config) {
      config = [:]
  }

  def cfg = defaultConfig + config

  def images = sh (
    script: 'docker images -f "reference=**/*citest" --format "{{.Repository}}"',
    returnStdout: true
  ).trim()

  def list = images.split("\n")

  for(int i = 0;i<list.size();i++) {
    def image = list[i].trim()

    if (!image) {
      return
    }

    println "Loading image ${image} on Kind cluster ${cfg.name}"

    sh """
      kind load docker-image ${image}:citest --name ${cfg.name} --nodes ${cfg.name}-control-plane,${cfg.name}-worker,${cfg.name}-worker2
    """
  }
}

// [EOF]
