// Copyright 2017-present Open Networking Foundation
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

PROFILE="null"
CORE_CONTAINER="null"

pipeline {

    /* no label, executor is determined by JJB */
    agent {
        label "${params.executorNode}"
    }

  stages {

    stage('repo') {
      steps {
        checkout(changelog: false, \
          poll: false,
          scm: [$class: 'RepoScm', \
            manifestRepositoryUrl: "${params.manifestUrl}", \
            manifestBranch: "${params.manifestBranch}", \
            currentBranch: true, \
            destinationDir: 'cord', \
            forceSync: true,
            resetFirst: true, \
            quiet: true, \
            jobs: 4, \
            showAllChanges: true] \
          )
      }
    }

    stage('patch') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           VERSIONFILE="" # file path to file containing version number
           NEW_VERSION="" # version number found in VERSIONFILE
           releaseversion=0

           function read_version {
             if [ -f "VERSION" ]
             then
               NEW_VERSION=\$(head -n1 "VERSION")
               VERSIONFILE="VERSION"
             elif [ -f "package.json" ]
             then
               NEW_VERSION=\$(python -c 'import json,sys;obj=json.load(sys.stdin); print obj["version"]' < package.json)
               VERSIONFILE="package.json"
             else
               echo "ERROR: No versioning file found!"
               exit 1
             fi
           }

           # check if the version is a released version
           function check_if_releaseversion {
             if [[ "\$NEW_VERSION" =~ ^([0-9]+)\\.([0-9]+)\\.([0-9]+)\$ ]]
             then
               echo "Version string '\$NEW_VERSION' in '\$VERSIONFILE' is a SemVer released version!"
               releaseversion=1
             else
               echo "Version string '\$NEW_VERSION' in '\$VERSIONFILE' is not a SemVer released version, skipping."
             fi
           }

           pushd cord
           PROJECT_PATH=\$(xmllint --xpath "string(//project[@name=\\\"${gerritProject}\\\"]/@path)" .repo/manifest.xml)
           repo download "\$PROJECT_PATH" "${gerritChangeNumber}/${gerritPatchsetNumber}"

           pushd \$PROJECT_PATH
           echo "Existing git tags:"
           git tag -n

           read_version
           check_if_releaseversion

           # perform checks if a released version
           if [ "\$releaseversion" -eq "1" ]
           then
             git config --global user.email "apitest@opencord.org"
             git config --global user.name "API Test"

             git tag -a "\$NEW_VERSION" -m "Tagged for api test on Gerrit patchset: ${gerritChangeNumber}"

             echo "Tags including new tag:"
             git tag -n

           fi
           popd
           popd
           """
      }
    }


    stage('prep') {
      parallel {

        stage('images') {
          steps {
            sh '''
               pushd cord/automation-tools/developer
               mkdir ib_logs
               ./imagebuilder.py -l ib_logs -a ib_actions.yml -g ib_graph.dot -f ../../helm-charts/examples/filter-images.yaml
               popd
               '''
            archiveArtifacts artifacts: 'cord/automation-tools/developer/ib_actions.yml, cord/automation-tools/developer/ib_graph.dot, cord/automation-tools/developer/ib_logs/*', fingerprint: true
          }
        }

        stage('minikube') {
          steps {
            /* see https://github.com/kubernetes/minikube/#linux-continuous-integration-without-vm-support */
            sh '''
               export MINIKUBE_WANTUPDATENOTIFICATION=false
               export MINIKUBE_WANTREPORTERRORPROMPT=false
               export CHANGE_MINIKUBE_NONE_USER=true
               export MINIKUBE_HOME=$HOME
               mkdir -p $HOME/.kube || true
               touch $HOME/.kube/config
               export KUBECONFIG=$HOME/.kube/config
               sudo -E /usr/bin/minikube start --vm-driver=none
               '''
            script {
              timeout(3) {
                waitUntil {
                  sleep 5
                  def kc_ret = sh script: "kubectl get po", returnStatus: true
                  return (kc_ret == 0);
                }
              }
            }
          }
        }
      }
    }
    stage('helm') {
      steps {
        sh '''
           helm init
           sleep 60
           helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com/
           '''
      }
    }
    stage('Build') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           helm_install_args='-f examples/image-tag-candidate.yaml -f examples/imagePullPolicy-IfNotPresent.yaml -f examples/api-test-values.yaml'
           basesleep=300
           extrasleep=60

           pushd cord/helm-charts

           helm dep up xos-core
           helm install \${helm_install_args} xos-core -n xos-core

           # Pick which chart(s) to load depending on the project being tested
           # In regex, please list repos in same order as requirements.yaml in the chart(s) loaded!

           if [[ "$GERRIT_PROJECT" =~ ^(rcord|onos-service|fabric|olt-service|vsg-hw|vrouter)\$ ]]; then
             helm dep update xos-profiles/rcord-lite
             helm install \${helm_install_args} xos-profiles/rcord-lite -n rcord-lite
             extrasleep=300

           elif [[ "$GERRIT_PROJECT" =~ ^(vMME|vspgwc|vspgwu|vHSS|hss_db|internetemulator|sdn-controller|epc-service|mcord|progran)\$ ]]; then
             helm dep update xos-profiles/mcord
             helm install \${helm_install_args}  xos-profiles/mcord -n mcord
             extrasleep=900

           elif [[ "$GERRIT_PROJECT" =~ ^(openstack|vtn-service|exampleservice|addressmanager)\$ ]]; then
             # NOTE: onos-service is included in base-openstack, but tested w/rcord-lite chart

             helm dep update xos-profiles/base-openstack
             helm dep update xos-profiles/demo-exampleservice
             helm install \${helm_install_args} xos-profiles/base-openstack -n base-openstack
             helm install \${helm_install_args} xos-profiles/demo-exampleservice -n demo-exampleservice

           elif [[ "$GERRIT_PROJECT" =~ ^(kubernetes-service|simpleexampleservice)\$ ]]; then
             helm dep update xos-profiles/base-kubernetes
             helm dep update xos-profiles/demo-simpleexampleservice
             helm install \${helm_install_args} xos-profiles/base-kubernetes -n base-kubernetes
             helm install \${helm_install_args} xos-profiles/demo-simpleexampleservice -n demo-simpleexampleservice

           elif [[ "$GERRIT_PROJECT" =~ ^(hippie-oss)\$ ]]; then
             helm dep update xos-services/hippie-oss
             helm install \${helm_install_args} xos-services/hippie-oss -n hippie-oss

           elif [[ "$GERRIT_PROJECT" =~ ^(xos|xos-tosca|cord-tester)\$ ]]; then
             echo "No additional charts to install for testing $GERRIT_PROJECT"

           else
             echo "Couldn't find a chart to test project: $GERRIT_PROJECT!"
             exit 1
           fi

           # sleep to wait for services to load
           sleep "\$basesleep"
           sleep "\$extrasleep"

           echo "# Checking helm deployments"
           kubectl get pods
           helm list

           for hchart in \$(helm list -q);
           do
             echo "## 'helm status' for chart: \${hchart} ##"
             helm status "\${hchart}"
           done

           popd
        """
      }
    }
    stage('Setup') {
      steps {
        sh """
            CORE_CONTAINER=\$(docker ps | grep k8s_xos-core | awk '{print \$1}')

            docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosapitests.xtarget
            docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosserviceapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosserviceapitests.xtarget
            docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xoslibrary.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xoslibrary.xtarget
            docker exec -i \$CORE_CONTAINER /bin/bash -c "xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosapitests.xtarget /opt/xos/core/models/core.xproto" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/XOSCoreAPITests.robot

            SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')

            export testname=_service_api.robot
            export library=_library.robot

            # do addtional tests if additional services are loaded
            if ! [[ "$GERRIT_PROJECT" =~ ^(xos|xos-tosca|cord-tester)\$ ]]; then
              for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosserviceapitests.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$testname; done
              for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xoslibrary.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$library; done
            fi
            """
        }
      }
    stage('Test') {
       steps {
          sh """
              pushd cord/test/cord-tester/src/test/cord-api/Tests
              CORE_CONTAINER=\$(docker ps | grep k8s_xos-core | awk '{print \$1}')
              CHAM_CONTAINER=\$(docker ps | grep k8s_xos-chameleon | awk '{print \$1}')
              XOS_CHAMELEON=\$(docker exec \$CHAM_CONTAINER ip a | grep -oE "([0-9]{1,3}\\.){3}[0-9]{1,3}\\b" | grep 172)
              export testname=_service_api.robot
              export library=_library.robot
              SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
              echo \$SERVICES
              cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Properties/
              sed -i \"s/^\\(SERVER_IP = \\).*/\\1\'\$XOS_CHAMELEON\'/\" RestApiProperties.py
              sed -i \"s/^\\(SERVER_PORT = \\).*/\\1\'9101\'/\" RestApiProperties.py
              sed -i \"s/^\\(XOS_USER = \\).*/\\1\'admin@opencord.org\'/\" RestApiProperties.py
              sed -i \"s/^\\(XOS_PASSWD = \\).*/\\1\'letmein\'/\" RestApiProperties.py
              sed -i \"s/^\\(PASSWD = \\).*/\\1\'letmein\'/\" RestApiProperties.py
              cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests
              pybot -d Log -T -e TenantWithContainer -e Port -e ControllerImages -e ControllerNetwork -e ControllerSlice -e ControllerUser XOSCoreAPITests.robot  || true
              if ! [[ "$GERRIT_PROJECT" =~ ^(cord|platform-install|xos|xos-tosca|cord-tester)\$ ]]; then
                  for i in \$SERVICES; do bash -c "pybot -d Log -T -e AddressManagerServiceInstance -v TESTLIBRARY:\$i\$library \$i\$testname"; sleep 2; done || true
              fi
              popd
            """
            }
    }
    stage('Publish') {
        steps {
            sh """
            if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
            cp -r $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/Log/*ml ./RobotLogs
            """
            step([$class: 'RobotPublisher',
                disableArchiveOutput: false,
                logFileName: 'RobotLogs/log*.html',
                otherFiles: '',
                outputFileName: 'RobotLogs/output*.xml',
                outputPath: '.',
                passThreshold: 95,
                reportFileName: 'RobotLogs/report*.html',
                unstableThreshold: 0]);
        }
    }
    }
    post {
        always {
            sh '''
              kubectl get pods --all-namespaces

              echo "# removing helm deployments"
              kubectl get pods
              helm list

              for hchart in \$(helm list -q);
              do
                echo "## Purging chart: \${hchart} ##"
                helm delete --purge "\${hchart}"
              done

              minikube delete
            '''
            step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "suchitra@opennetworking.org, you@opennetworking.org, kailash@opennetworking.org", sendToIndividuals: false])
        }
    }
}
