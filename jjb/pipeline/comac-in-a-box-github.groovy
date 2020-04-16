// Copyright 2020-present Open Networking Foundation
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

// comac-in-a-box-github build+test
// steps taken from https://guide.opencord.org/profiles/comac/install/ciab.html

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }

  options {
    timeout(time: 1, unit: 'HOURS')
  }

  stages {
    stage ("Publish Docker Image"){
      steps {
        // TODO: add docker-publish downstream job
      }
    }

    stage ("Change Helm-Charts Docker Tags"){
      steps {
        // TODO: change tags
      }
    }

    stage ("Run COMAC-in-a-box"){
      steps {
        sh label: 'Run Makefile', script: '''
          HOME=$(pwd)
          cd automation-tools/comac-in-a-box/
          sudo make reset-test
          sudo make test
          '''
      }
    }
  }
}
