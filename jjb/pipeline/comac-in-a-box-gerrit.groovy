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

// comac-in-a-box-gerrit build+test
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
    stage ("Environment Setup"){
      steps {
        sh label: 'Run COMAC-in-a-box make', script: """
          echo $HOME
          cd $HOME/automation-tools/comac-in-a-box/
          sudo make reset-test
          sudo make   # This does not take any time if make had already run.
          """
        sh label: 'helm-charts Repo Fresh Clone', script: """
          cd $HOME/cord/
          sudo rm -rf helm-charts/
          sudo git clone https://gerrit.opencord.org/helm-charts
          """
      }
    }

    stage ("Fetch Helm-Charts Changes"){
      steps {
        sh label: 'Fetch helm-charts Gerrit Changes', script: """
          cd $HOME/cord/helm-charts/
          if [ ! -z "${GERRIT_REFSPEC}" ]
          then
            echo "Checking out Gerrit patchset: ${GERRIT_REFSPEC}"
            sudo git fetch ${gitUrl} ${GERRIT_REFSPEC} && git checkout FETCH_HEAD
          else
            echo "GERRIT_REFSPEC not provided. Checking out master branch."
            sudo git checkout master
          fi
          """
      }
    }

    stage ("Run COMAC-in-a-box"){
      steps {
        sh label: 'Run Makefile', script: """
          cd $HOME/automation-tools/comac-in-a-box/
          sudo make reset-test
          sudo make test
          """
      }
    }
  }
}
