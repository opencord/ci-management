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

// chart-api-test-helm.groovy
// Checks functionality of the helm-chart, without overriding the version/tag used

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

    stage('helm') {
      steps {
        sh '''
           helm init
           sleep 60
           helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com/
           '''
      }
    }

    stage('cord-kafka') {
      steps {
        sh '''
           #!/usr/bin/env bash
           set -eu -o pipefail

           pushd cord/helm-charts
           helm install -f examples/kafka-single.yaml --version 0.8.8 -n cord-kafka incubator/kafka
           ./scripts/wait_for_pods.sh

           popd
           '''
      }
    }

    stage('install/test att-workflow') {
      when {
        expression {
          params.manifestBranch ==~ 'master'
        }
      }
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           helm_install_args='-f examples/api-test-values.yaml'

           pushd cord/helm-charts

           helm dep up xos-core
           helm install \${helm_install_args} xos-core -n xos-core

           helm dep update xos-profiles/att-workflow
           helm install \${helm_install_args} xos-profiles/att-workflow -n att-workflow

           # wait for services to load
           PODS_TIMEOUT=900 ./scripts/wait_for_pods.sh

           echo "# Checking helm deployments"
           kubectl get pods

           for hchart in \$(helm list -q);
           do
             echo "## 'helm status' for chart: \${hchart} ##"
             helm status "\${hchart}"
           done

           CORE_POD=\$(kubectl get pods | grep "xos-core.*Running" | awk '{print \$1}')
           CORE_CONTAINER=\$(docker ps | grep k8s_xos-core_\${CORE_POD} | awk '{print \$1}')

           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosapitests.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosserviceapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosserviceapitests.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xoslibrary.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xoslibrary.xtarget
           docker exec -i \$CORE_CONTAINER /bin/bash -c "xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosapitests.xtarget /opt/xos/core/models/core.xproto" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/XOSCoreAPITests.robot

           export testname=_service_api.robot
           export library=_library.robot

           SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
           echo \$SERVICES

           for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosserviceapitests.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$testname; done

           for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xoslibrary.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$library; done

           CHAM_CONTAINER=\$(docker ps | grep k8s_xos-chameleon | awk '{print \$1}')
           XOS_CHAMELEON=\$(docker exec \$CHAM_CONTAINER ip a | grep -oE "([0-9]{1,3}\\.){3}[0-9]{1,3}\\b" | grep 172)

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Properties/
           sed -i \"s/^\\(SERVER_IP = \\).*/\\1\'\$XOS_CHAMELEON\'/\" RestApiProperties.py
           sed -i \"s/^\\(SERVER_PORT = \\).*/\\1\'9101\'/\" RestApiProperties.py
           sed -i \"s/^\\(XOS_USER = \\).*/\\1\'admin@opencord.org\'/\" RestApiProperties.py
           sed -i \"s/^\\(XOS_PASSWD = \\).*/\\1\'letmein\'/\" RestApiProperties.py
           sed -i \"s/^\\(PASSWD = \\).*/\\1\'letmein\'/\" RestApiProperties.py

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests
           ## Run CORE API Tests
           robot -d Log -T XOSCoreAPITests.robot  || true
           ## Run services API Tests
           for i in \$SERVICES; do bash -c "robot -d Log -T -v TESTLIBRARY:\$i\$library \$i\$testname"; sleep 2; done || true

           popd

           helm delete --purge att-workflow
           helm delete --purge xos-core
           """
      }
    }

    stage('install/test rcord-lite') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           helm_install_args='-f examples/api-test-values.yaml'

           pushd cord/helm-charts

           helm dep up xos-core
           helm install \${helm_install_args} xos-core -n xos-core

           helm dep update xos-profiles/rcord-lite
           helm install \${helm_install_args} xos-profiles/rcord-lite -n rcord-lite

           # wait for services to load
           PODS_TIMEOUT=900 ./scripts/wait_for_pods.sh

           echo "# Checking helm deployments"
           kubectl get pods

           for hchart in \$(helm list -q);
           do
             echo "## 'helm status' for chart: \${hchart} ##"
             helm status "\${hchart}"
           done

           CORE_POD=\$(kubectl get pods | grep "xos-core.*Running" | awk '{print \$1}')
           CORE_CONTAINER=\$(docker ps | grep k8s_xos-core_\${CORE_POD} | awk '{print \$1}')

           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosapitests.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosserviceapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosserviceapitests.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xoslibrary.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xoslibrary.xtarget
           docker exec -i \$CORE_CONTAINER /bin/bash -c "xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosapitests.xtarget /opt/xos/core/models/core.xproto" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/XOSCoreAPITests.robot

           export testname=_service_api.robot
           export library=_library.robot

           SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
           echo \$SERVICES

           for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosserviceapitests.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$testname; done

           for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xoslibrary.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$library; done

           CHAM_CONTAINER=\$(docker ps | grep k8s_xos-chameleon | awk '{print \$1}')
           XOS_CHAMELEON=\$(docker exec \$CHAM_CONTAINER ip a | grep -oE "([0-9]{1,3}\\.){3}[0-9]{1,3}\\b" | grep 172)

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Properties/
           sed -i \"s/^\\(SERVER_IP = \\).*/\\1\'\$XOS_CHAMELEON\'/\" RestApiProperties.py
           sed -i \"s/^\\(SERVER_PORT = \\).*/\\1\'9101\'/\" RestApiProperties.py
           sed -i \"s/^\\(XOS_USER = \\).*/\\1\'admin@opencord.org\'/\" RestApiProperties.py
           sed -i \"s/^\\(XOS_PASSWD = \\).*/\\1\'letmein\'/\" RestApiProperties.py
           sed -i \"s/^\\(PASSWD = \\).*/\\1\'letmein\'/\" RestApiProperties.py

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests
           ## Run CORE API Tests
           robot -d Log -T XOSCoreAPITests.robot  || true
           ## Run Rcord-lite services API Tests
           for i in \$SERVICES; do bash -c "robot -d Log -T -v TESTLIBRARY:\$i\$library \$i\$testname"; sleep 2; done || true

           popd

           helm delete --purge rcord-lite
           helm delete --purge xos-core
           """
      }
    }

    stage('install/test mcord') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           helm_install_args='-f examples/api-test-values.yaml'

           pushd cord/helm-charts

           helm dep up xos-core
           helm install \${helm_install_args} xos-core -n xos-core

           helm dep update xos-profiles/base-openstack
           helm dep update xos-profiles/mcord
           helm install \${helm_install_args} xos-profiles/base-openstack -n base-openstack
           helm install \${helm_install_args} xos-profiles/mcord -n mcord

           # wait for services to load
           PODS_TIMEOUT=900 ./scripts/wait_for_pods.sh

           echo "# Checking helm deployments"
           kubectl get pods

           for hchart in \$(helm list -q);
           do
             echo "## 'helm status' for chart: \${hchart} ##"
             helm status "\${hchart}"
           done

           CORE_POD=\$(kubectl get pods | grep "xos-core.*Running" | awk '{print \$1}')
           CORE_CONTAINER=\$(docker ps | grep k8s_xos-core_\${CORE_POD} | awk '{print \$1}')

           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosapitests.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosserviceapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosserviceapitests.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xoslibrary.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xoslibrary.xtarget
           docker exec -i \$CORE_CONTAINER /bin/bash -c "xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosapitests.xtarget /opt/xos/core/models/core.xproto" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/XOSCoreAPITests.robot

           export testname=_service_api.robot
           export library=_library.robot

           SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
           echo \$SERVICES

           for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosserviceapitests.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$testname; done

           for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xoslibrary.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$library; done

           CHAM_CONTAINER=\$(docker ps | grep k8s_xos-chameleon | awk '{print \$1}')
           XOS_CHAMELEON=\$(docker exec \$CHAM_CONTAINER ip a | grep -oE "([0-9]{1,3}\\.){3}[0-9]{1,3}\\b" | grep 172)

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Properties/
           sed -i \"s/^\\(SERVER_IP = \\).*/\\1\'\$XOS_CHAMELEON\'/\" RestApiProperties.py

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests
           ## Run mcord services API Tests
           for i in \$SERVICES; do bash -c "robot -d Log -T -e ProgranServiceInstance -v TESTLIBRARY:\$i\$library \$i\$testname"; sleep 2; done || true

           popd

           helm delete --purge base-openstack
           helm delete --purge mcord
           helm delete --purge xos-core
           """
      }
    }

    stage('install/test simpleexampleservice') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           helm_install_args='-f examples/api-test-values.yaml'

           pushd cord/helm-charts

           helm dep up xos-core
           helm install \${helm_install_args} xos-core -n xos-core

           helm dep update xos-profiles/base-kubernetes
           helm dep update xos-profiles/demo-simpleexampleservice
           helm install \${helm_install_args} xos-profiles/base-kubernetes -n base-kubernetes
           helm install \${helm_install_args} xos-profiles/demo-simpleexampleservice -n demo-simpleexampleservice

           # wait for services to load
           PODS_TIMEOUT=900 ./scripts/wait_for_pods.sh

           echo "# Checking helm deployments"
           kubectl get pods

           for hchart in \$(helm list -q);
           do
             echo "## 'helm status' for chart: \${hchart} ##"
             helm status "\${hchart}"
           done

           CORE_POD=\$(kubectl get pods | grep "xos-core.*Running" | awk '{print \$1}')
           CORE_CONTAINER=\$(docker ps | grep k8s_xos-core_\${CORE_POD} | awk '{print \$1}')

           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosapitests.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosserviceapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosserviceapitests.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xoslibrary.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xoslibrary.xtarget
           docker exec -i \$CORE_CONTAINER /bin/bash -c "xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosapitests.xtarget /opt/xos/core/models/core.xproto" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/XOSCoreAPITests.robot

           # run e2e synchronizer test
           helm test demo-simpleexampleservice

           export testname=_service_api.robot
           export library=_library.robot

           SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
           echo \$SERVICES

           for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosserviceapitests.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$testname; done

           for i in \$SERVICES; do bash -c "docker exec -i \$CORE_CONTAINER /bin/bash -c 'xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xoslibrary.xtarget /opt/xos/dynamic_services/\$i/\$i.xproto /opt/xos/core/models/core.xproto'" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/\$i\$library; done

           CHAM_CONTAINER=\$(docker ps | grep k8s_xos-chameleon | awk '{print \$1}')
           XOS_CHAMELEON=\$(docker exec \$CHAM_CONTAINER ip a | grep -oE "([0-9]{1,3}\\.){3}[0-9]{1,3}\\b" | grep 172)

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Properties/
           sed -i \"s/^\\(SERVER_IP = \\).*/\\1\'\$XOS_CHAMELEON\'/\" RestApiProperties.py

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests
           ## Run kubernetes-base services API Tests
           for i in \$SERVICES; do bash -c "robot -d Log -T -v TESTLIBRARY:\$i\$library \$i\$testname"; sleep 2; done || true

           popd

           helm delete --purge base-kubernetes
           helm delete --purge demo-simpleexampleservice
           helm delete --purge xos-core
           """
      }
    }

    stage('install/test hippie-oss') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           helm_install_args='-f examples/api-test-values.yaml'

           pushd cord/helm-charts

           helm dep up xos-core
           helm install \${helm_install_args} xos-core -n xos-core

           helm dep update xos-services/hippie-oss
           helm install \${helm_install_args} xos-services/hippie-oss -n hippie-oss

           # wait for services to load
           PODS_TIMEOUT=900 ./scripts/wait_for_pods.sh

           echo "# Checking helm deployments"
           kubectl get pods

           for hchart in \$(helm list -q);
           do
             echo "## 'helm status' for chart: \${hchart} ##"
             helm status "\${hchart}"
           done

           CORE_POD=\$(kubectl get pods | grep "xos-core.*Running" | awk '{print \$1}')
           CORE_CONTAINER=\$(docker ps | grep k8s_xos-core_\${CORE_POD} | awk '{print \$1}')

           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosapitests.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xosserviceapitests.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xosserviceapitests.xtarget
           docker cp $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/targets/xoslibrary.xtarget \$CORE_CONTAINER:/opt/xos/lib/xos-genx/xosgenx/targets/xoslibrary.xtarget
           docker exec -i \$CORE_CONTAINER /bin/bash -c "xosgenx --target /opt/xos/lib/xos-genx/xosgenx/targets/./xosapitests.xtarget /opt/xos/core/models/core.xproto" > $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/XOSCoreAPITests.robot

           export testname=_service_api.robot
           export library=_library.robot

           SERVICES=\$(docker exec -i \$CORE_CONTAINER /bin/bash -c "cd /opt/xos/dynamic_services/;find -name '*.xproto'" | awk -F[//] '{print \$2}')
           echo \$SERVICES

           cd $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests
           ## Run hippie-oss services API Tests
           for i in \$SERVICES; do bash -c "robot -d Log -T -v TESTLIBRARY:\$i\$library \$i\$testname"; sleep 2; done || true

           popd

           helm delete --purge hippie-oss
           helm delete --purge xos-core
           """
      }
    }
  }
  post {
    always {
      sh '''
         # copy robot logs
         if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
         cp -r $WORKSPACE/cord/test/cord-tester/src/test/cord-api/Tests/Log/*ml ./RobotLogs

         #copy helm test robot logs
         cp -r /tmp/*ml ./RobotLogs

         kubectl get pods --all-namespaces

         echo "# removing helm deployments"
         for hchart in \$(helm list -q);
         do
           echo "## Purging chart: \${hchart} ##"
           helm delete --purge "\${hchart}"
         done

         sudo minikube delete
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/output*.xml',
            outputPath: '.',
            passThreshold: 100,
            reportFileName: 'RobotLogs/report*.html',
            unstableThreshold: 0]);
         step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "kailash@opennetworking.org", sendToIndividuals: false])
    }
  }
}
