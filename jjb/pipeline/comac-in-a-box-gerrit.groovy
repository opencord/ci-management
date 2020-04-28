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
        sh label: 'Run COMAC-in-a-box reset-test', script: """
          echo $HOME
          cd $HOME/automation-tools/comac-in-a-box/
          sudo make reset-test
          """
        sh label: 'Cleanup Docker Images', script: '''
          sudo docker rmi -f $(sudo docker images --format '{{.Repository}} {{.ID}}' | grep 'none' | awk '{print $2}') || true
          sudo docker rmi -f $(sudo docker images --format '{{.Repository}}:{{.Tag}}' | grep 'openmme') || true
          sudo docker rmi -f $(sudo docker images --format '{{.Repository}}:{{.Tag}}' | grep 'ngic') || true
          sudo docker rmi -f $(sudo docker images --format '{{.Repository}}:{{.Tag}}' | grep 'c3po') || true
          '''
        sh label: 'helm-charts Repo Fresh Clone', script: """
          cd $HOME/cord/
          sudo rm -rf helm-charts/
          git clone https://gerrit.opencord.org/helm-charts
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
            git fetch ${gitUrl} ${GERRIT_REFSPEC} && git checkout FETCH_HEAD
          else
            echo "GERRIT_REFSPEC not provided. Checking out master branch."
            git checkout master
          fi
          """
      }
    }

    stage ("Run COMAC-in-a-box"){
      steps {
        script{
          try{
            sh label: 'Run Makefile', script: """
              cd $HOME/automation-tools/comac-in-a-box/
              sudo make reset-test
              sudo make test
              """
          } finally {
            sh label: 'Archive Logs', script: '''
              mkdir logs
              mkdir logs/pods
              kubectl get pods -n omec > logs/kubectl_get_pods_omec.log
              for pod in $(kubectl get pods -n omec | awk '{print $1}' | tail -n +2)
              do
                kubectl logs -n omec $pod > logs/pods/$pod.log || true
              done
            '''
            archiveArtifacts artifacts: "logs/**/*.log", allowEmptyArchive: true
          }
        }
      }
    }
  }
}
