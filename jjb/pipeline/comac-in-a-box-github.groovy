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
    omec_cp = "~/cord/helm-charts/omec/omec-control-plane/values.yaml"
    omec_dp = "~/cord/helm-charts/omec/omec-data-plane/values.yaml"
  }

  stages {
    stage ("Publish Docker Image"){
      steps {
        script {
          pull_request_num = "PR_${params.ghprbPullId}"
          abbreviated_commit_hash = params.ghprbActualCommit.substring(0, 7)
          docker_tag = "${params.ghprbTargetBranch}-${pull_request_num}-${abbreviated_commit_hash}"
        }
        build job: "docker-publish-github_${params.project}", parameters: [
              string(name: 'gitUrl', value: "https://github.com/${params.ghprbGhRepository}"),
              string(name: 'gitRef', value: "pull/${params.ghprbPullId}/head"),
              string(name: 'branchName', value: "${docker_tag}"),
              string(name: 'projectName', value: "${params.project}"),
            ]
      }
    }

    stage ("Change Helm-Charts Docker Tags"){
      steps {
        sh label: 'Change Helm-Charts Docker Tags', script: """
          HOME=$(pwd)
          if [ "${params.project}" = "c3po" ]
          then
            sed -i "s;hssdb: .*;hssdb: \\"${params.registry}/c3po-hssdb:${docker_tag}\\";" ${omec_cp}
            sed -i "s;hss: .*;hss: \\"${params.registry}/c3po-hss:${docker_tag}\\";" ${omec_cp}
            echo "Changed hssdb and hss tag."
          elif [ "${params.project}" = "openmme" ]
          then
            sed -i "s;mme: .*;mme: \\"${params.registry}/openmme:${docker_tag}\\";" ${omec_cp}
            echo "Changed mme tag."
          elif [ "${params.project}" = "ngic-rtc" ]
          then
            sed -i "s;spgwc: .*;spgwc: \\"${params.registry}/ngic-cp:${docker_tag}\\";" ${omec_cp}
            sed -i "s;spgwu: .*;spgwu: \\"${params.registry}/ngic-dp:${docker_tag}\\";" ${omec_dp}
            echo "Changed spgwc and spgwu tag."
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
