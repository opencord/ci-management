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

// Jenkinsfile-omec-deploy-staging.groovy

pipeline {

  /* executor is determined by parameter */
  agent {
    label "${params.buildNode}"
  }

  environment {
    omec_cp = "~/pod-configs/deployment-configs/aether/apps/gcp-stg/omec-cp.yaml"
    omec_dp = "~/pod-configs/deployment-configs/aether/apps/menlo-stg/omec-dp.yaml"
  }

  stages {
    stage('Clean Workspace'){
        steps {
            sh "rm -rf *"
        }
    }
    stage('Determine Image Tags (Cron Trigger Only)') {
        when {
            equals expected: false, actual: params.manual_run
        }
        steps {
            script {
                primaryBranches = readJSON file: ".OMECprimaryBranches.json"
                hssdb_tag = """${primaryBranches['c3po']}-${sh script: "git ls-remote https://github.com/omec-project/c3po ${primaryBranches['c3po']} | awk '{print \$1}'", returnStdout: true}""".trim()
                hss_tag = """${primaryBranches['c3po']}-${sh script: "git ls-remote https://github.com/omec-project/c3po ${primaryBranches['c3po']} | awk '{print \$1}'", returnStdout: true}""".trim()
                mme_tag = """${primaryBranches['openmme']}-${sh script: "git ls-remote https://github.com/omec-project/openmme ${primaryBranches['openmme']} | awk '{print \$1}'", returnStdout: true}""".trim()
                mmeExporter_tag = ""
                spgwc_tag = """${primaryBranches['ngic-rtc']}-${sh script: "git ls-remote https://github.com/omec-project/ngic-rtc ${primaryBranches['ngic-rtc']} | awk '{print \$1}'", returnStdout: true}""".trim()
                spgwu_tag = """${primaryBranches['ngic-rtc']}-${sh script: "git ls-remote https://github.com/omec-project/ngic-rtc ${primaryBranches['ngic-rtc']} | awk '{print \$1}'", returnStdout: true}""".trim()
            }
        }
    }

    stage('Get Image Tags from Params (Manual Trigger Only)') {
        when {
            equals expected: true, actual: params.manual_run
        }
        steps {
            script {
                hssdb_tag = "${params.hssdb_tag}"
                hss_tag = "${params.hss_tag}"
                mme_tag = "${params.mme_tag}"
                mmeExporter_tag = "${params.mmeExporter_tag}"
                spgwc_tag = "${params.spgwc_tag}"
                spgwu_tag = "${params.spgwu_tag}"
            }
        }
    }

    stage('Clone Pod-Configs') {
        steps {
            sh label: "Fresh clone of pod-configs in vm",script: """
              ssh comac@192.168.122.57 '
                rm -rf pod-configs/
                git clone https://gerrit.opencord.org/pod-configs/
              '
          """
        }
    }

    stage('Change Staging Images Config') {
      steps {
        sh label: 'Fetch Images from Params', script: """
          ssh comac@192.168.122.57 '
            if [ ! -z "${hssdb_tag}" ]
            then
              sed -i "s;hssdb: .*;hssdb: \\"${registry}/c3po-hssdb:${hssdb_tag}\\";" ${env.omec_cp}
              echo "Changed hssdb tag."
            else
              echo "hssdb tag missing. Not changing."
            fi

            if [ ! -z "${hss_tag}" ]
            then
              sed -i "s;hss: .*;hss: \\"${registry}/c3po-hss:${hss_tag}\\";" ${env.omec_cp}
              echo "Changed hss tag."
            else
              echo "hss tag missing. Not changing."
            fi

            if [ ! -z "${mme_tag}" ]
            then
              sed -i "s;mme: .*;mme: \\"${registry}/openmme:${mme_tag}\\";" ${env.omec_cp}
              echo "Changed mme tag."
            else
              echo "mme tag missing. Not changing."
            fi

            if [ ! -z "${mmeExporter_tag}" ]
            then
              sed -i "s;mmeExporter: .*;mmeExporter: \\"${registry}/mme-exporter:${mmeExporter_tag}\\";" ${env.omec_cp}
              echo "Changed mmeExporter tag."
            else
              echo "mmeExporter tag missing. Not changing."
            fi

            if [ ! -z "${spgwc_tag}" ]
            then
              sed -i "s;spgwc: .*;spgwc: \\"${registry}/ngic-cp:${spgwc_tag}\\";" ${env.omec_cp}
              echo "Changed spgwc tag."
            else
              echo "spgwc tag missing. Not changing."
            fi

            if [ ! -z "${spgwu_tag}" ]
            then
              sed -i "s;spgwu: .*;spgwu: \\"${registry}/ngic-dp:${spgwu_tag}\\";" ${env.omec_dp}
              echo "Changed spgwu tag."
            else
              echo "spgwu tag missing. Not changing."
            fi

            echo "omec_cp:"
            cat ${env.omec_cp}

            echo "omec_dp:"
            cat ${env.omec_dp}
            '
          """
      }
    }

    stage('Deploy: staging-central-gcp') {
      steps {
        sh label: 'staging-central-gcp', script: '''
          ssh comac@192.168.122.57 '
            kubectl config use-context staging-central-gcp

            helm del --purge omec-control-plane | true

            helm install --kube-context staging-central-gcp \
                         --name omec-control-plane \
                         --namespace omec \
                         --values pod-configs/deployment-configs/aether/apps/gcp-stg/omec-cp.yaml \
                         cord/omec-control-plane

            kubectl --context staging-central-gcp -n omec wait \
                         --for=condition=Ready \
                         --timeout=300s \
                         pod -l app=spgwc
            '
          '''
      }
    }

    stage('Deploy: omec-data-plane') {
      steps {
        sh label: 'staging-edge-onf-menlo', script: '''
          ssh comac@192.168.122.57 '
            kubectl config use-context staging-edge-onf-menlo

            helm del --purge omec-data-plane | true

            helm install --kube-context staging-edge-onf-menlo \
                         --name omec-data-plane \
                         --namespace omec \
                         --values pod-configs/deployment-configs/aether/apps/menlo-stg/omec-dp.yaml \
                         cord/omec-data-plane

            kubectl --context staging-edge-onf-menlo -n omec wait \
                         --for=condition=Ready \
                         --timeout=300s \
                         pod -l app=spgwu
            '
          '''
      }
    }

    stage('Deploy: accelleran-cbrs') {
      steps {
        sh label: 'accelleran-cbrs-common', script: '''
          ssh comac@192.168.122.57 '
            kubectl config use-context staging-edge-onf-menlo

            helm del --purge accelleran-cbrs-common | true
            helm del --purge accelleran-cbrs-cu | true

            helm install --kube-context staging-edge-onf-menlo \
                         --name accelleran-cbrs-common \
                         --namespace omec \
                         --values pod-configs/deployment-configs/aether/apps/menlo-stg/accelleran-cbrs-common.yaml \
                         cord/accelleran-cbrs-common

            helm install --kube-context staging-edge-onf-menlo \
                         --name accelleran-cbrs-cu \
                         --namespace omec \
                         --values pod-configs/deployment-configs/aether/apps/menlo-stg/accelleran-cbrs-cu.yaml \
                         cord/accelleran-cbrs-cu

            kubectl --context staging-edge-onf-menlo -n omec wait \
                         --for=condition=Ready \
                         --timeout=300s \
                         pod -l app=accelleran-cbrs-cu
            '
          '''
      }
    }
  }
}
