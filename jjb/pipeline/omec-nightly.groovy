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

// omec-nightly.groovy
// Nightly job that deploys OMEC with latest images and runs NG40 scaling/stress tests

pipeline {

  agent {
    label "${params.buildNode}"
  }

  stages {
    stage ("Environment Cleanup"){
      steps {
        step([$class: 'WsCleanup'])
      }
    }

    stage ("Deploy and Test"){
      options {
        lock(resource: 'aether-dev-cluster')
      }
      steps {
        script {
          try {
            runTest = false
            build job: "omec_deploy_dev", parameters: [
                  string(name: 'hssdbImage', value: ""),
                  string(name: 'hssImage', value: ""),
                  string(name: 'mmeImage', value: ""),
                  string(name: 'spgwcImage', value: ""),
                  string(name: 'bessImage', value: ""),
                  string(name: 'zmqifaceImage', value: ""),
                  string(name: 'pfcpifaceImage', value: ""),
            ]
            runTest = true
            build job: "omec_ng40-test_dev", parameters: [
                  string(name: 'ntlFile', value: "${params.ntlFile}"),
                  string(name: 'timeout', value: "300")
            ]
            currentBuild.result = 'SUCCESS'
          } catch (err) {
            currentBuild.result = 'FAILURE'
            throw err
          } finally {
            // Collect and copy OMEC logs
            build job: "omec_archive-artifacts_dev"
            copyArtifacts projectName: 'omec_archive-artifacts_dev', target: 'omec', selector: lastCompleted()
            archiveArtifacts artifacts: "omec/*/*", allowEmptyArchive: true

            if (runTest) {
              // Copy NG40 logs
              copyArtifacts projectName: 'omec_ng40-test_dev', target: 'ng40', selector: lastCompleted()
              archiveArtifacts artifacts: "ng40/*/*", allowEmptyArchive: true

              // Extract results from NG40 logs and generate csv files
              log_list = sh returnStdout: true, script: """
              cd ng40/log
              ls *.log | grep -v interactive | grep -v ng40test | grep -v ATTACH
              """
              log_list = log_list.trim()
              for( String log_name : log_list.split() ) {
                sh returnStdout: true, script: """
                export NG40_LOG_FILE=${log_name}
                /home/jenkins/extract-ng40-results.sh
                """
              }

              // Get csv files and plot
              csv_list = sh returnStdout: true, script: """
              cd ng40/log
              ls *.csv
              """
              csv_list = csv_list.trim()
              for( String csv_name : csv_list.split() ) {
                // FIXME: it plots each csv on a separated graph
                plot csvFileName: 'plot-8e54e334-ab7b-4c9f-94f7-b9d8965723df.csv',
                     csvSeries: [[ displayTableFlag: true,
                                   file: "ng40/log/${csv_name}"]],
                     group: 'scale',
                     logarithmic: true,
                     style: 'line',
                     numBuilds: '20',
                     title: 'NG40 Scaling',
                     yaxis: 'Number of UEs',
                     yaxisMinimum: "0.1"
              }
            }
          }
        }
      }
    }
  }
}
