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
    timeout(time: 90, unit: 'MINUTES')
  }
  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="$WORKSPACE/kind-voltha/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    TYPE="minimal"
    FANCY=0
    WITH_SIM_ADAPTERS="n"
    WITH_RADIUS="y"
    WITH_BBSIM="y"
    DEPLOY_K8S="y"
    VOLTHA_LOG_LEVEL="DEBUG"
    CONFIG_SADIS="n"
    ROBOT_MISC_ARGS="-d $WORKSPACE/RobotLogs -v teardown_device:False"
  }

  stages {

    stage('Repo') {
      steps {
        step([$class: 'WsCleanup'])
        checkout(changelog: false, \
          poll: false,
          scm: [$class: 'RepoScm', \
            manifestRepositoryUrl: "${params.manifestUrl}", \
            manifestBranch: "${params.manifestBranch}", \
            currentBranch: true, \
            destinationDir: 'voltha', \
            forceSync: true,
            resetFirst: true, \
            quiet: true, \
            jobs: 4, \
            showAllChanges: true] \
          )
      }
    }
    stage('Patch') {
      steps {
        sh """
           pushd voltha
           PROJECT_PATH=\$(xmllint --xpath "string(//project[@name=\\\"${gerritProject}\\\"]/@path)" .repo/manifest.xml)
           repo download "\$PROJECT_PATH" "${gerritChangeNumber}/${gerritPatchsetNumber}"
           popd
           """
      }
    }
    stage('Create K8s Cluster') {
      steps {
        sh """
           git clone https://github.com/ciena/kind-voltha.git
           cd kind-voltha/
           JUST_K8S=y ./voltha up
           bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/kind-voltha/bin"
           """
      }
    }

    stage('Build Images') {
      steps {
        sh """
           if ! [[ "${gerritProject}" =~ ^(voltha-helm-charts|voltha-system-tests)\$ ]]; then
             cd $WORKSPACE/voltha/${gerritProject}/
             make DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
           fi
           """
      }
    }

    stage('Push Images') {
      steps {
        sh '''
           if ! [[ "${gerritProject}" =~ ^(voltha-helm-charts|voltha-system-tests)\$ ]]; then
             export GOROOT=/usr/local/go
             export GOPATH=\$(pwd)
             docker images | grep citest
             for image in \$(docker images -f "reference=*/*citest" --format "{{.Repository}}"); do echo "Pushing \$image to nodes"; kind load docker-image \$image:citest --name voltha-\$TYPE --nodes voltha-\$TYPE-worker,voltha-\$TYPE-worker2; done
           fi
           '''
      }
    }
    stage('Deploy Voltha') {
      steps {
        sh '''
           export EXTRA_HELM_FLAGS="--set log_agent.enabled=False ${extraHelmFlags} "

           IMAGES=""
           if [ "${gerritProject}" = "voltha-go" ]; then
             IMAGES="rw_core ro_core "
           elif [ "${gerritProject}" = "ofagent-py" ]; then
             IMAGES="ofagent "
           elif [ "${gerritProject}" = "voltha-onos" ]; then
             IMAGES="onos "
           elif [ "${gerritProject}" = "voltha-openolt-adapter" ]; then
             IMAGES="adapter_open_olt "
           elif [ "${gerritProject}" = "voltha-openonu-adapter" ]; then
             IMAGES="adapter_open_onu "
           elif [ "${gerritProject}" = "voltha-api-server" ]; then
             IMAGES="afrouter afrouterd "
           elif [ "${gerritProject}" = "bbsim" ]; then
             IMAGES="bbsim "
           else
             echo "No images to push"
           fi

           for I in \$IMAGES
           do
             EXTRA_HELM_FLAGS+="--set images.\$I.tag=citest,images.\$I.pullPolicy=Never "
           done

           if [ "${gerritProject}" = "voltha-helm-charts" ]; then
             export CHART_PATH=$WORKSPACE/voltha/voltha-helm-charts
             export VOLTHA_CHART=\$CHART_PATH/voltha
             export VOLTHA_ADAPTER_OPEN_OLT_CHART=\$CHART_PATH/voltha-adapter-openolt
             export VOLTHA_ADAPTER_OPEN_ONU_CHART=\$CHART_PATH/voltha-adapter-openonu
             helm dep update \$VOLTHA_CHART
             helm dep update \$VOLTHA_ADAPTER_OPEN_OLT_CHART
             helm dep update \$VOLTHA_ADAPTER_OPEN_ONU_CHART
           fi

           cd $WORKSPACE/kind-voltha/
           echo \$EXTRA_HELM_FLAGS
           kail -n voltha -n default > $WORKSPACE/onos-voltha-combined.log &
           ./voltha up
           '''
      }
    }

    stage('ONOS Config') {
      steps {
        sh '''
          if [[ ${onosVersion} == "1.13.10" ]]; then
            curl -sSL --user karaf:karaf \
              -X POST \
              -H Content-Type:application/json \
              http://localhost:8181/onos/v1/network/configuration/apps \
              --data @- << EOF
              {
                 "org.opencord.sadis":{
                    "sadis":{
                       "integration":{
                          "cache":{
                             "enabled":false,
                             "maxsize":50,
                             "ttl":"PT0m"
                          }
                       },
                       "entries":[
                          {
                             "id":"BBSIM_OLT_0",
                             "hardwareIdentifier":"0f:f1:ce:c0:ff:ee",
                             "nasId":"BBSIMOLT000",
                             "uplinkPort":1048576
                          },
                          {
                             "id":"BBSM00000001-1",
                             "nasPortId":"BBSM00000001-1",
                             "circuitId":"BBSM00000001-1",
                             "remoteId":"BBSM00000001-1",
                             "uniTagList":[
                                {
                                   "ponCTag":900,
                                   "ponSTag":900,
                                   "technologyProfileId":64,
                                   "downstreamBandwidthProfile":"Default",
                                   "upstreamBandwidthProfile":"Default",
                                   "isDhcpRequired":true
                                }
                             ]
                          }
                       ]
                    },
                    "bandwidthprofile":{
                       "integration":{
                          "cache":{
                             "enabled":true,
                             "maxsize":40,
                             "ttl":"PT1m"
                          }
                       },
                       "entries":[
                          {
                             "id":"Default",
                             "cir":1000000,
                             "cbs":1001,
                             "eir":1002,
                             "ebs":1003,
                             "air":1004
                          }
                       ]
                    }
                 }
              }
            EOF
            sshpass -p karaf ssh -p 30115 karaf@${deployment_config.nodes[0].ip} "cfg set org.opencord.olt.impl.OltFlowService enableDhcpOnProvisioning true"
            sshpass -p karaf ssh -p 30115 karaf@${deployment_config.nodes[0].ip} "cfg set org.opencord.olt.impl.OltFlowService enableDhcpV4 true"
            sshpass -p karaf ssh -p 30115 karaf@${deployment_config.nodes[0].ip} "cfg set org.opencord.olt.impl.OltFlowService enableEapol true"
        else
            echo "Using kind-voltha defaults"
        fi
        '''
      }
    }

    stage('Run E2E Tests') {
      steps {
        sh '''
           mkdir -p $WORKSPACE/RobotLogs

           # By default, all tests tagged 'sanity' are run.  This covers basic functionality
           # like running through the ATT workflow for a single subscriber.
           export TEST_TAGS=sanity

           # If the Gerrit comment contains a line with "functional tests" then run the full
           # functional test suite.  This covers tests tagged either 'sanity' or 'functional'.
           # Note: Gerrit comment text will be prefixed by "Patch set n:" and a blank line
           REGEX="functional tests"
           if [[ "$GERRIT_EVENT_COMMENT_TEXT" =~ \$REGEX ]]; then
             TEST_TAGS=sanityORfunctional
           fi

           make -C $WORKSPACE/voltha/voltha-system-tests single-kind || true
           '''
      }
    }
  }

  post {
    always {
      sh '''
         set +e
         cp $WORKSPACE/kind-voltha/install-minimal.log $WORKSPACE/
         kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\t'}{.imageID}{'\\n'}" | sort | uniq -c
         kubectl get nodes -o wide
         kubectl get pods -o wide
         kubectl get pods -n voltha -o wide

         sync
         pkill kail || true

         ## Pull out errors from log files
         extract_errors_go() {
           echo
           echo "Error summary for $1:"
           grep $1 $WORKSPACE/onos-voltha-combined.log | grep '"level":"error"' | cut -d ' ' -f 2- | jq -r '.msg'
           echo
         }

         extract_errors_python() {
           echo
           echo "Error summary for $1:"
           grep $1 $WORKSPACE/onos-voltha-combined.log | grep 'ERROR' | cut -d ' ' -f 2-
           echo
         }

         extract_errors_go voltha-rw-core > $WORKSPACE/error-report.log
         extract_errors_go adapter-open-olt >> $WORKSPACE/error-report.log
         extract_errors_python adapter-open-onu >> $WORKSPACE/error-report.log
         extract_errors_python voltha-ofagent >> $WORKSPACE/error-report.log

         gzip $WORKSPACE/onos-voltha-combined.log

         ## shut down kind-voltha
         cd $WORKSPACE/kind-voltha
	       WAIT_ON_DOWN=y ./voltha down
         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/output*.xml',
            outputPath: '.',
            passThreshold: 80,
            reportFileName: 'RobotLogs/report*.html',
            unstableThreshold: 0]);
         archiveArtifacts artifacts: '*.log,*.gz'

    }
  }
}
