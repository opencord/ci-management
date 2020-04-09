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

// Jenkinsfile-omec-deploy-staging.groovy: Changes staging images in
// omec-cp.yaml and omec-dp.yaml based on params and deploys omec staging.
// Mainly triggered from omec-postmerge after publishing docker images.

dp_context = ""
omec_cp = ""
omec_dp = ""
accelleran_cbrs_common = ""
accelleran_cbrs_cu = ""

pipeline {

  /* executor is determined by parameter */
  agent {
    label "${params.buildNode}"
  }

  stages {
    stage('Select kubectl Configuration') {
        steps {
            script {
                if (params.useProductionCluster) {
                    dp_context = "production-edge-demo"
                    omec_cp = "~/pod-configs/deployment-configs/aether/apps/gcp-prd/omec-cp.yaml"
                    omec_dp = "~/pod-configs/deployment-configs/aether/apps/menlo-demo/omec-dp-cbrs.yaml"
                    accelleran_cbrs_common = "~/pod-configs/deployment-configs/aether/apps/menlo-demo/accelleran-cbrs-common.yaml"
                    accelleran_cbrs_cu = "~/pod-configs/deployment-configs/aether/apps/menlo-demo/accelleran-cbrs-cu.yaml"
                } else {
                    dp_context = "staging-edge-onf-menlo"
                    omec_cp = "~/pod-configs/deployment-configs/aether/apps/gcp-stg/omec-cp.yaml"
                    omec_dp = "~/pod-configs/deployment-configs/aether/apps/menlo-stg/omec-dp.yaml"
                    accelleran_cbrs_common = "~/pod-configs/deployment-configs/aether/apps/menlo-stg/accelleran-cbrs-common.yaml"
                    accelleran_cbrs_cu = "~/pod-configs/deployment-configs/aether/apps/menlo-stg/accelleran-cbrs-cu.yaml"
                }
            }
            sh label: 'Select kubectl Configuration', script: """
              ssh comac@192.168.122.57 '
                if ${params.useProductionCluster}
                then
                    cp ~/.kube/omec_production_config ~/.kube/config
                else
                    cp ~/.kube/omec_staging_config ~/.kube/config
                fi
              '
            """
        }
    }
    stage('Change Staging Images Config') {
      steps {
        sh label: 'Change Staging Images Config', script: """
          ssh comac@192.168.122.57 '

            # if hssdb tag is provided, change hssdb tag in omec_cp.yaml.
            if [ ! -z "${params.hssdb_tag}" ]
            then
              sed -i "s;hssdb: .*;hssdb: \\"${params.registry}/c3po-hssdb:${params.hssdb_tag}\\";" ${omec_cp}
              echo "Changed hssdb tag."
            else
              echo "hssdb tag not provided. Not changing."
            fi

            # if hss tag is provided, change hss tag in omec_cp.yaml.
            if [ ! -z "${params.hss_tag}" ]
            then
              sed -i "s;hss: .*;hss: \\"${params.registry}/c3po-hss:${params.hss_tag}\\";" ${omec_cp}
              echo "Changed hss tag."
            else
              echo "hss tag not provided. Not changing."
            fi

            # if mme tag is provided, change mme tag in omec_cp.yaml.
            if [ ! -z "${params.mme_tag}" ]
            then
              sed -i "s;mme: .*;mme: \\"${params.registry}/openmme:${params.mme_tag}\\";" ${omec_cp}
              echo "Changed mme tag."
            else
              echo "mme tag not provided. Not changing."
            fi

            # if mmeExporter tag is provided, change mmeExporter tag in omec_cp.yaml.
            if [ ! -z "${params.mmeExporter_tag}" ]
            then
              sed -i "s;mmeExporter: .*;mmeExporter: \\"${params.registry}/mme-exporter:${params.mmeExporter_tag}\\";" ${omec_cp}
              echo "Changed mmeExporter tag."
            else
              echo "mmeExporter tag not provided. Not changing."
            fi

            # if spgwc tag is provided, change spgwc tag in omec_cp.yaml.
            if [ ! -z "${params.spgwc_tag}" ]
            then
              sed -i "s;spgwc: .*;spgwc: \\"${params.registry}/ngic-cp:${params.spgwc_tag}\\";" ${omec_cp}
              echo "Changed spgwc tag."
            else
              echo "spgwc tag not provided. Not changing."
            fi

            # if spgwu tag is provided, change spgwu tag in omec_dp.yaml.
            if [ ! -z "${params.spgwu_tag}" ]
            then
              sed -i "s;spgwu: .*;spgwu: \\"${params.registry}/ngic-dp:${params.spgwu_tag}\\";" ${omec_dp}
              echo "Changed spgwu tag."
            else
              echo "spgwu tag not provided. Not changing."
            fi

            # display omec-cp.yaml
            echo "omec_cp:"
            cat ${omec_cp}

            # display omec-dp.yaml
            echo "omec_dp:"
            cat ${omec_dp}
            '
          """
      }
    }

    stage('Deploy Control Plane') {
      steps {
        sh label: 'staging-central-gcp', script: """
          ssh comac@192.168.122.57 '
            kubectl config use-context staging-central-gcp

            helm del --purge omec-control-plane | true

            helm install --kube-context staging-central-gcp \
                         --name omec-control-plane \
                         --namespace omec \
                         --values ${omec_cp} \
                         cord/omec-control-plane

            kubectl --context staging-central-gcp -n omec wait \
                         --for=condition=Ready \
                         --timeout=300s \
                         pod -l app=spgwc
            '
          """
      }
    }

    stage('Deploy Data Plane') {
      steps {
        sh label: 'dp_context', script: """
          ssh comac@192.168.122.57 '
            kubectl config use-context ${dp_context}

            helm del --purge omec-data-plane | true

            helm install --kube-context ${dp_context} \
                         --name omec-data-plane \
                         --namespace omec \
                         --values ${omec_dp} \
                         cord/omec-data-plane

            kubectl --context ${dp_context} -n omec wait \
                         --for=condition=Ready \
                         --timeout=300s \
                         pod -l app=spgwu
            '
          """
      }
    }

    stage('Deploy Accelleran CBRS') {
      steps {
        sh label: 'accelleran-cbrs-common', script: """
          ssh comac@192.168.122.57 '
            kubectl config use-context ${dp_context}

            helm del --purge accelleran-cbrs-common | true
            helm del --purge accelleran-cbrs-cu | true

            helm install --kube-context ${dp_context} \
                         --name accelleran-cbrs-common \
                         --namespace omec \
                         --values ${accelleran_cbrs_common} \
                         cord/accelleran-cbrs-common

            helm install --kube-context ${dp_context} \
                         --name accelleran-cbrs-cu \
                         --namespace omec \
                         --values ${accelleran_cbrs_cu} \
                         cord/accelleran-cbrs-cu

            kubectl --context ${dp_context} -n omec wait \
                         --for=condition=Ready \
                         --timeout=300s \
                         pod -l app=accelleran-cbrs-cu
            '
          """
      }
    }
  }
}
