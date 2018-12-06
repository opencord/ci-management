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
           pushd cord
           PROJECT_PATH=\$(xmllint --xpath "string(//project[@name=\\\"${gerritProject}\\\"]/@path)" .repo/manifest.xml)
           repo download "\$PROJECT_PATH" "${gerritChangeNumber}/${gerritPatchsetNumber}"
           popd
           """
      }
    }
    stage('Build') {
      steps {
        sh """
        if [[ "$GERRIT_PROJECT" =~ ^(platform-install|xos|cord|rcord|vrouter|vsg|vtn-service|vtr|fabric|openstack|chameleon|exampleservice|simpleexampleservice|onos-service|olt-service|cord-tester|kubernetes-service)\$ ]]; then
            PROFILE=rcord-local.yml
        fi
        if [[ "$GERRIT_PROJECT" =~ ^(ecord|vEE|vEG)\$ ]]; then
            PROFILE=ecord-local.yml
        fi
        if [[ "$GERRIT_PROJECT" =~ ^(mcord|vspgwu|venb|vspgwc|vEPC)\$ ]]; then
            PROFILE=mcord-ng40-local.yml
        fi
        if [[ "$GERRIT_PROJECT" =~ ^(vMME|vHSS|hss_db|epc-service|internetemulator|sdn-controller)\$ ]]; then
            PROFILE=mcord-cavium-local.yml
        fi
        cd $WORKSPACE/cord/build/
        make PODCONFIG=\$PROFILE config
        make -j4 build
        if [ '$GERRIT_BRANCH' = 'master' ]; then
          make xos-wait-dynamicload
        fi
        """
      }
    }
    stage('Setup') {
      steps {
        sh """
        if [[ "$GERRIT_PROJECT" =~ ^(platform-install|xos|cord|rcord|vrouter|vsg|vtn-service|vtr|fabric|openstack|chameleon|exampleservice|simpleexampleservice|onos-service|olt-service|cord-tester|kubernetes-service)\$ ]]; then
            CORE_CONTAINER=rcord_xos_core_1
        fi
        if [[ "$GERRIT_PROJECT" =~ ^(ecord|vEE|vEG)\$ ]]; then
            CORE_CONTAINER=ecord_xos_core_1
        fi
        if [[ "$GERRIT_PROJECT" =~ ^(mcord|vspgwu|venb|vspgwc|vEPC)\$ ]]; then
            CORE_CONTAINER=mcordng40_xos_core_1
        fi
        if [[ "$GERRIT_PROJECT" =~ ^(vMME|vHSS|hss_db|epc-service|internetemulator|sdn-controller)\$ ]]; then
            CORE_CONTAINER=mcordcavium_xos_core_1
        fi
        docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosapitests.xtarget
        docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosserviceapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosserviceapitests.xtarget
        docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xoslibrary.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xoslibrary.xtarget
        docker exec -i \$CORE_CONTAINER /bin/bash -c "xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosapitests.xtarget /opt/xos/core/models/core.xproto" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/XOSCoreAPITests.robot
        SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
        export testname=_service_api.robot
        export library=_library.robot
        for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosserviceapitests.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$testname; done
        for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xoslibrary.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$library; done
        """
        }
      }
    stage('Test') {
        steps {
            sh """
            if [[ "$GERRIT_PROJECT" =~ ^(platform-install|xos|cord|rcord|vrouter|vsg|vtn-service|vtr|fabric|openstack|chameleon|exampleservice|simpleexampleservice|onos-service|olt-service|cord-tester|kubernetes-service)\$ ]]; then
                CORE_CONTAINER=rcord_xos_core_1
            fi
            if [[ "$GERRIT_PROJECT" =~ ^(ecord|vEE|vEG)\$ ]]; then
                CORE_CONTAINER=ecord_xos_core_1
            fi
            if [[ "$GERRIT_PROJECT" =~ ^(mcord|vspgwu|venb|vspgwc|vEPC)\$ ]]; then
                CORE_CONTAINER=mcordng40_xos_core_1
            fi
            if [[ "$GERRIT_PROJECT" =~ ^(vMME|vHSS|hss_db|epc-service|internetemulator|sdn-controller)\$ ]]; then
                CORE_CONTAINER=mcordcavium_xos_core_1
            fi
                export testname=_service_api.robot
                export library=_library.robot
                SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
                echo \$SERVICES
                export SERVER_IP=localhost
                export SERVER_PORT=9101
                export XOS_USER=xosadmin@opencord.org
                export XOS_PASSWD=\$(cat $WORKSPACE/cord/build/platform-install/credentials/xosadmin@opencord.org)
                cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Properties/
                sed -i \"s/^\\(SERVER_IP = \\).*/\\1\'127.0.0.1\'/\" RestApiProperties.py
                sed -i \"s/^\\(SERVER_PORT = \\).*/\\1\'9101\'/\" RestApiProperties.py
                sed -i \"s/^\\(XOS_USER = \\).*/\\1\'xosadmin@opencord.org\'/\" RestApiProperties.py
                sed -i \"s/^\\(XOS_PASSWD = \\).*/\\1\'\$(cat $WORKSPACE/cord/build/platform-install/credentials/xosadmin@opencord.org)\'/\" RestApiProperties.py
                sed -i \"s/^\\(PASSWD = \\).*/\\1\'\$(cat $WORKSPACE/cord/build/platform-install/credentials/xosadmin@opencord.org)\'/\" RestApiProperties.py
                cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests
                robot -d Log -T -e TenantWithContainer -e Port -e ControllerImages -e ControllerNetwork -e ControllerSlice -e ControllerUser XOSCoreAPITests.robot  || true
                for i in \$SERVICES; do bash -c "robot -d Log -T -e AddressManagerServiceInstance -v TESTLIBRARY:\$i\$library \$i\$testname"; sleep 2; done || true
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
                passThreshold: 100,
                reportFileName: 'RobotLogs/report*.html',
                unstableThreshold: 0]);
        }
    }
    }
    post {
        always {
            step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "kailash@opennetworking.org", sendToIndividuals: false])
        }
    }
}
