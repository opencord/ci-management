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
               ./imagebuilder.py -f ../../helm-charts/examples/api-test-images.yaml
               popd
               '''
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
           pushd cord/helm-charts
           helm dep up xos-core
           helm install -f examples/api-test-values.yaml xos-core -n xos-core
           sleep 300
           helm status xos-core
           if [[ "$GERRIT_PROJECT" =~ ^(rcord|vrouter|vsg|vtn|vtr|fabric|openstack|chameleon|exampleservice|simpleexampleservice|onos-service|olt-service|kubernetes-service|hippie-oss|vsg-hw)\$ ]]; then
               helm dep update xos-profiles/rcord-lite
               helm install xos-profiles/rcord-lite -n rcord-lite
               sleep 360

           fi
           if [[ "$GERRIT_PROJECT" =~ ^(mcord|vspgwu|venb|vspgwc|vEPC|vMME|vHSS|hss_db|epc-service|internetemulator|sdn-controller)\$ ]]; then
               helm dep update xos-profiles/mcord
               helm install xos-profiles/mcord -n mcord
               sleep 900
           fi
           helm status xos-core
           kubectl get pods
           helm ls
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
            if ! [[ "$GERRIT_PROJECT" =~ ^(cord|platform-install|xos|xos-tosca|cord-tester)\$ ]]; then
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
                passThreshold: 94,
                reportFileName: 'RobotLogs/report*.html',
                unstableThreshold: 0]);
        }
    }
    }
    post {
        always {
            sh '''
              kubectl get pods --all-namespaces
              helm list
              helm delete --purge xos-core
              if [[ "$GERRIT_PROJECT" =~ ^(rcord|vrouter|vsg|vtn|vtr|fabric|openstack|chameleon|exampleservice|simpleexampleservice|onos-service|olt-service|kubernetes-service|hippie-oss|vsg-hw)\$ ]]; then
                helm delete --purge rcord-lite
              fi
              if [[ "$GERRIT_PROJECT" =~ ^(mcord|vspgwu|venb|vspgwc|vEPC|vMME|vHSS|hss_db|epc-service|internetemulator|sdn-controller)\$ ]]; then
                helm delete --purge mcord
              fi
              minikube delete
            '''
            step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "suchitra@opennetworking.org, you@opennetworking.org, kailash@opennetworking.org", sendToIndividuals: false])
        }
    }
}
