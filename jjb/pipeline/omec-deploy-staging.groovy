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

pipeline {

  /* executor is determined by parameter */
  agent {
    label "${params.buildNode}"
  }

  /* locations of omec-cp.yaml and omec-dp.yaml */
  environment {
    omec_cp = "~/pod-configs/deployment-configs/aether/apps/gcp-stg/omec-cp.yaml"
    omec_dp = "~/pod-configs/deployment-configs/aether/apps/menlo-stg/omec-dp.yaml"
  }

  stages {
    stage('Select kubectl Configuration') {
        steps {
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
              sed -i "s;hssdb: .*;hssdb: \\"${params.registry}/c3po-hssdb:${params.hssdb_tag}\\";" ${env.omec_cp}
              echo "Changed hssdb tag."
            else
              echo "hssdb tag not provided. Not changing."
            fi

            # if hss tag is provided, change hss tag in omec_cp.yaml.
            if [ ! -z "${params.hss_tag}" ]
            then
              sed -i "s;hss: .*;hss: \\"${params.registry}/c3po-hss:${params.hss_tag}\\";" ${env.omec_cp}
              echo "Changed hss tag."
            else
              echo "hss tag not provided. Not changing."
            fi

            # if mme tag is provided, change mme tag in omec_cp.yaml.
            if [ ! -z "${params.mme_tag}" ]
            then
              sed -i "s;mme: .*;mme: \\"${params.registry}/openmme:${params.mme_tag}\\";" ${env.omec_cp}
              echo "Changed mme tag."
            else
              echo "mme tag not provided. Not changing."
            fi

            # if mmeExporter tag is provided, change mmeExporter tag in omec_cp.yaml.
            if [ ! -z "${params.mmeExporter_tag}" ]
            then
              sed -i "s;mmeExporter: .*;mmeExporter: \\"${params.registry}/mme-exporter:${params.mmeExporter_tag}\\";" ${env.omec_cp}
              echo "Changed mmeExporter tag."
            else
              echo "mmeExporter tag not provided. Not changing."
            fi

            # if spgwc tag is provided, change spgwc tag in omec_cp.yaml.
            if [ ! -z "${params.spgwc_tag}" ]
            then
              sed -i "s;spgwc: .*;spgwc: \\"${params.registry}/ngic-cp:${params.spgwc_tag}\\";" ${env.omec_cp}
              echo "Changed spgwc tag."
            else
              echo "spgwc tag not provided. Not changing."
            fi

            # if spgwu tag is provided, change spgwu tag in omec_dp.yaml.
            if [ ! -z "${params.spgwu_tag}" ]
            then
              sed -i "s;spgwu: .*;spgwu: \\"${params.registry}/ngic-dp:${params.spgwu_tag}\\";" ${env.omec_dp}
              echo "Changed spgwu tag."
            else
              echo "spgwu tag not provided. Not changing."
            fi

            # display omec-cp.yaml
            echo "omec_cp:"
            cat ${env.omec_cp}

            # display omec-dp.yaml
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
