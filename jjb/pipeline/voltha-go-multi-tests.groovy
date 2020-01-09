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

// voltha-2.x e2e tests
// uses kind-voltha to deploy voltha-2.X
// uses bbsim to simulate OLT/ONUs

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 40, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$WORKSPACE/kind-voltha/bin"
    TYPE="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="n"
    WITH_RADIUS="y"
    WITH_BBSIM="y"
    DEPLOY_K8S="y"
    VOLTHA_LOG_LEVEL="DEBUG"
    CONFIG_SADIS="n"
    EXTRA_HELM_FLAGS="${params.extraHelmFlags}"
    ROBOT_MISC_ARGS="${params.extraRobotArgs} -d $WORKSPACE/RobotLogs -v teardown_device:False"
  }
  stages {

    stage('Repo') {
      steps {
        step([$class: 'WsCleanup'])
        checkout(changelog: true,
          poll: false,
          scm: [$class: 'RepoScm',
            manifestRepositoryUrl: "${params.manifestUrl}",
            manifestBranch: "${params.manifestBranch}",
            currentBranch: true,
            destinationDir: 'voltha',
            forceSync: true,
            resetFirst: true,
            quiet: true,
            jobs: 4,
            showAllChanges: true]
          )
      }
    }

    stage('Download kind-voltha') {
      steps {
        sh """
           git clone https://github.com/ciena/kind-voltha.git
           """
      }
    }

    stage('Deploy Voltha') {
      steps {
        sh """
           cd kind-voltha/
           ./voltha up
           """
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''

           clear_etcd() {
             kubectl -n voltha exec $(kubectl -n voltha get pods -lapp=etcd -o=name) -- sh -c "ETCDCTL_API=3 etcdctl del --prefix $1"
           }

           wait_for_state_change() {
             timeout 60 bash -c "until voltctl device list -f Type=$2,AdminState=$3,OperStatus=$4,ConnectStatus=$5 -q | wc -l | grep -q 1; do echo Waiting for $1 to change states; voltctl device list; echo; sleep 5; done"
           }

           mkdir -p $WORKSPACE/RobotLogs
           git clone https://gerrit.opencord.org/voltha-system-tests
           cd kind-voltha
           for i in \$(seq 1 ${testRuns})
           do
             make -C $WORKSPACE/voltha-system-tests ${makeTarget}
             echo "Completed run: \$i"
             echo ""
             if [[ \$i -lt ${testRuns} ]]
             then
               # For testing multiple back-to-back runs
               # Doing some manual cleanup to work around known issues in BBSim and ONOS apps

               OLT=\$( voltctl device list -f Type=openolt -q )
               ONU=\$( voltctl device list -f Type=brcm_openomci_onu -q )

               voltctl device disable \$OLT
               wait_for_state_change OLT openolt DISABLED UNKNOWN REACHABLE
               wait_for_state_change ONU brcm_openomci_onu ENABLED DISCOVERED UNREACHABLE

               voltctl device disable \$ONU
               wait_for_state_change ONU brcm_openomci_onu DISABLED UNKNOWN UNREACHABLE

               voltctl device delete \$OLT

               timeout 60 bash -c 'until voltctl device list -q | wc -l | grep -q 0; do echo "Waiting for all devices to be deleted"; voltctl device list; echo ""; sleep 5; done'

               helm delete --purge bbsim # VOL-2342
               helm delete --purge onos # VOL-2343, VOL-2363
               clear_etcd service/voltha/resource_manager
               clear_etcd service/voltha/openolt
               clear_etcd service/voltha/devices
               sleep 30
               DEPLOY_K8S=no ./voltha up  # Will just re-deploy BBSim and ONOS
             fi
           done
           '''
      }
    }
  }

  post {
    always {
      sh '''
         set +e
         cd kind-voltha/
         cp install-minimal.log $WORKSPACE/
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
         kubectl get nodes -o wide
         kubectl get pods -o wide
         kubectl get pods -n voltha -o wide
         ## get default pod logs
         for pod in \$(kubectl get pods --no-headers | awk '{print \$1}');
         do
           if [[ \$pod == *"onos"* && \$pod != *"onos-service"* ]]; then
             kubectl logs \$pod onos> $WORKSPACE/\$pod.log;
           else
             kubectl logs \$pod> $WORKSPACE/\$pod.log;
           fi
         done
         ## get voltha pod logs
         for pod in \$(kubectl get pods --no-headers -n voltha | awk '{print \$1}');
         do
           if [[ \$pod == *"-api-"* ]]; then
             kubectl logs \$pod arouter -n voltha > $WORKSPACE/\$pod.log;
           else
             kubectl logs \$pod -n voltha > $WORKSPACE/\$pod.log;
           fi
         done
         ## shut down voltha
         WAIT_ON_DOWN=y ./voltha down
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
         archiveArtifacts artifacts: '*.log'

    }
  }
}
