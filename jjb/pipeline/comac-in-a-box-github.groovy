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

docker_tag = ""
abbreviated_commit_hash = ""

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }

  options {
    timeout(time: 1, unit: 'HOURS')
  }

  environment {

    omec_cp = "$HOME/cord/helm-charts/omec/omec-control-plane/values.yaml"
    omec_dp = "$HOME/cord/helm-charts/omec/omec-data-plane/values.yaml"
  }

  stages {
    stage ("Environment Setup"){
      steps {
        sh label: 'Clean Logs', script: """
          rm -rf logs/
          """
        sh label: 'Run COMAC-in-a-box reset-test', script: """
          echo $HOME
          cd $HOME/automation-tools/comac-in-a-box/
          sudo make reset-test
          """
        sh label: 'helm-charts Repo Fresh Clone', script: """
          cd $HOME/cord/
          sudo rm -rf helm-charts/
          git clone https://gerrit.opencord.org/helm-charts
          """
      }
    }

    stage ("Build Local Docker Image"){
      steps {
        script {
          if (params.ghprbPullId == ""){
            docker_tag = "jenkins_debug"
          } else {
            pull_request_num = "PR_${params.ghprbPullId}"
            abbreviated_commit_hash = params.ghprbActualCommit.substring(0, 7)
            docker_tag = "${params.branch}-${pull_request_num}-${abbreviated_commit_hash}"
          }
        }
        sh label: 'Clone repo, then make docker-build', script: """
          rm -rf ${params.project}
          if [ "${params.project}" = "c3po" ]
          then
            git clone https://github.com/omec-project/${params.project} --recursive
          else
            git clone https://github.com/omec-project/${params.project}
          fi
          cd ${params.project}
          if [ ! -z "${params.ghprbPullId}" ]
          then
            echo "Checking out GitHub Pull Request: ${params.ghprbPullId}"
            git fetch origin pull/${params.ghprbPullId}/head && git checkout FETCH_HEAD
          else
            echo "GERRIT_REFSPEC not provided. Checking out target branch."
            git checkout ${params.branch}
          fi
          sudo make DOCKER_TAG=${docker_tag} docker-build
        """

      }
    }

    stage ("Change Helm-Charts Docker Tags"){
      steps {
        sh label: 'Change Helm-Charts Docker Tags', script: """
          if [ "${params.project}" = "c3po" ]
          then
            sed -i "s;hssdb: docker.*;hssdb: \\"c3po-hssdb:${docker_tag}\\";" ${omec_cp}
            sed -i "s;hss: .*;hss: \\"c3po-hss:${docker_tag}\\";" ${omec_cp}
            echo "Changed hssdb and hss tag: ${docker_tag}"
          elif [ "${params.project}" = "openmme" ]
          then
            sed -i "s;mme: .*;mme: \\"openmme:${docker_tag}\\";" ${omec_cp}
            echo "Changed mme tag: ${docker_tag}"
          elif [ "${params.project}" = "Nucleus" ]
          then
            sed -i "s;mme: .*;mme: \\"nucleus:${docker_tag}\\";" ${omec_cp}
            echo "Changed mme tag: ${docker_tag}"
          elif [ "${params.project}" = "ngic-rtc" ]
          then
            sed -i "s;spgwc: .*;spgwc: \\"ngic-cp:${docker_tag}\\";" ${omec_cp}
            sed -i "s;spgwu: .*;spgwu: \\"ngic-dp:${docker_tag}-debug\\";" ${omec_dp}
            echo "Changed spgwc and spgwu tag: ${docker_tag}"
          else
            echo "The project ${params.project} is not supported. Aborting job."
            exit 1
          fi

          echo "omec_cp:"
          cat "${omec_cp}"

          echo "omec_dp:"
          cat "${omec_dp}"
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
                kubectl logs -n omec $pod --all-containers > logs/pods/$pod.log || true
              done
            '''
            archiveArtifacts artifacts: "logs/**/*.log", allowEmptyArchive: true
          }
        }
      }
    }
  }
}
