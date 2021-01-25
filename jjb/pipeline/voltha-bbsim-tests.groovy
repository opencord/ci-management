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
// uses bbsim to simulate OLT/ONUs

library identifier: 'cord-jenkins-libraries@master',
    //'master' refers to a valid git-ref
    //'mylibraryname' can be any name you like
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://github.com/teone/cord-jenkins-libraries.git'
])

def clusterName = "voltha-test"
def extraHelmFlags = " --set global.image_registry=mirror.registry.opennetworking.org/ "
def logLevel = "DEBUG"

def customImageFlags(image) {
  return "--set images.${image}.tag=citest,images.${image}.pullPolicy=Never "
}

def test_workflow(name) {
  stage('Deploy VOLTHA') {
    steps {
      volthaDeploy([onosReplica: 3])
    }
  }
  stage("${name.toUpperCase()} workflow") {
    timeout(time: 15, unit: 'MINUTES') {
      sh """
      # Install VOLTHA

        helm upgrade --install voltha-infra onf/voltha-infra ${extraHelmFlags} \
          --set global.log_level="DEBUG" \
          -f $WORKSPACE/voltha-helm-charts/examples/${name}-values.yaml

        helm upgrade --install voltha1 onf/voltha-stack ${extraHelmFlags} \
          --set global.stack_name=voltha1 \
          --set global.voltha_infra_name=voltha-infra \
          --set global.voltha_infra_namespace=default \
          --set global.log_level=DEBUG

        helm upgrade --install bbsim0 onf/bbsim ${extraHelmFlags} \
          --set olt_id="10" \
          --set onu=1,pon=1 \
          --set global.log_level=debug \
          -f $WORKSPACE/voltha-helm-charts/examples/${name}-values.yaml

        # start logging
        mkdir -p $WORKSPACE/att
        _TAG=kail-${name} kail -n default > $WORKSPACE/${name}/onos-voltha-combined.log &

        # TODO wait for VOLTHA to start

        # forward ports
        # forward ONOS and VOLTHA ports
        _TAG=onos-port-forward kubectl port-forward --address 0.0.0.0 -n default svc/voltha-infra-onos-classic-hs 8101:8101&
        _TAG=onos-port-forward kubectl port-forward --address 0.0.0.0 -n default svc/voltha-infra-onos-classic-hs 8181:8181&
        _TAG=voltha-port-forward kubectl port-forward --address 0.0.0.0 -n default svc/voltha1-voltha-api 55555:55555&
      """
      sh """
      ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/${name.toUpperCase()}Workflow"
      mkdir -p \$ROBOT_LOGS_DIR
      export ROBOT_MISC_ARGS="-d \$ROBOT_LOGS_DIR -e PowerSwitch"

      # By default, all tests tagged 'sanity' are run.  This covers basic functionality
      # like running through the ATT workflow for a single subscriber.
      if [[ "${name}" == "att" ]]; then
        # FIXME resolve this special case voltha-syste-test/Makefile
        export TARGET=sanity-kind
      else
        export TARGET=sanity-kind-${name}
      fi

      # If the Gerrit comment contains a line with "functional tests" then run the full
      # functional test suite.  This covers tests tagged either 'sanity' or 'functional'.
      # Note: Gerrit comment text will be prefixed by "Patch set n:" and a blank line
      REGEX="functional tests"
      if [[ "$GERRIT_EVENT_COMMENT_TEXT" =~ \$REGEX ]]; then
        if [[ "${name}" == "att" ]]; then
          # FIXME resolve this special case voltha-syste-test/Makefile
          export TARGET=functional-single-kind
        else
          export TARGET=functional-single-kind-${name}
        fi
      fi

      export VOLTCONFIG=$HOME/.volt/config
      export KUBECONFIG=$HOME/.kube/config

      # Run the specified tests
      make -C $WORKSPACE/voltha-system-tests \$TARGET || true
      """
      sh """
      # stop logging
      P_IDS="\$(ps e -ww -A | grep "_TAG=kail-${name}" | grep -v grep | awk '{print \$1}')"
      if [ -n "\$P_IDS" ]; then
        echo \$P_IDS
        for P_ID in \$P_IDS; do
          kill -9 \$P_ID
        done
      fi

      # remove orphaned port-forward from different namespaces
      ps aux | grep port-forw | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9

      # get pods information
      kubectl get pods --all-namespaces -o wide > \$WORKSPACE/${name}/pods.txt || true
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq | tee \$WORKSPACE/att/pod-images.txt || true
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq | tee \$WORKSPACE/att/pod-imagesId.txt || true
      """
    }
  }
}

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: 90, unit: 'MINUTES')
  }
  environment {
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    KUBECONFIG="$HOME/.kube/kind-config-${clusterName}"
  }

  stages {
    stage('Clone voltha-system-tests') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/voltha-system-tests",
            refspec: "${volthaSystemTestsChange}"
          ]],
          branches: [[ name: "${branch}", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-system-tests"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        sh """
        if [ '${volthaSystemTestsChange}' != '' ] ; then
          cd $WORKSPACE/voltha-system-tests
          git fetch https://gerrit.opencord.org/voltha-system-tests ${volthaSystemTestsChange} && git checkout FETCH_HEAD
        fi
        """
      }
    }
    stage('Clone voltha-helm-charts') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/voltha-helm-charts",
            // refspec: "${volthaHelmChartsChange}"
          ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-helm-charts"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        // script {
        //   sh(script:"""
        //     if [ '${volthaHelmChartsChange}' != '' ] ; then
        //       cd $WORKSPACE/voltha-helm-charts;
        //       git fetch https://gerrit.opencord.org/voltha-helm-charts ${volthaHelmChartsChange} && git checkout FETCH_HEAD
        //     fi
        //     """)
        // }
      }
    }
    // If the repo under test is not kind-voltha
    // then download it and checkout the patch
    stage('Download Patch') {
      when {
        expression {
          // TODO remove once we stop testing kind-voltha
          return "${gerritProject}" != 'kind-voltha';
        }
      }
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[
            url: "https://gerrit.opencord.org/${gerritProject}",
            refspec: "${gerritRefspec}"
          ]],
          branches: [[ name: "${branch}", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "${gerritProject}"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        sh """
          pushd $WORKSPACE/${gerritProject}
          git fetch https://gerrit.opencord.org/${gerritProject} ${gerritRefspec} && git checkout FETCH_HEAD

          echo "Currently on commit: \n"
          git log -1 --oneline
          popd
          """
      }
    }
    // If the repo under test is kind-voltha we don't need to download it again,
    // as we already have it, simply checkout the patch
    stage('Checkout kind-voltha patch') {
      when {
        expression {
          // TODO remove once we stop testing kind-voltha
          return "${gerritProject}" == 'kind-voltha';
        }
      }
      steps {
        sh """
        cd $WORKSPACE/kind-voltha
        git fetch https://gerrit.opencord.org/kind-voltha ${gerritRefspec} && git checkout FETCH_HEAD
        """
      }
    }
    stage('Create K8s Cluster') {
      steps {
        createKubernetesCluster([nodes: 3])
      }
    }

    stage('Build Images') {
      steps {
        sh """
           make-local () {
             make -C $WORKSPACE/\$1 DOCKER_REGISTRY=mirror.registry.opennetworking.org/ DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
           }
           if [ "${gerritProject}" = "pyvoltha" ]; then
             make -C $WORKSPACE/pyvoltha/ dist
             export LOCAL_PYVOLTHA=$WORKSPACE/pyvoltha/
             make-local voltha-openonu-adapter
           elif [ "${gerritProject}" = "voltha-lib-go" ]; then
             make -C $WORKSPACE/voltha-lib-go/ build
             export LOCAL_LIB_GO=$WORKSPACE/voltha-lib-go/
             make-local voltha-go
             make-local voltha-openolt-adapter
           elif [ "${gerritProject}" = "voltha-protos" ]; then
             make -C $WORKSPACE/voltha-protos/ build
             export LOCAL_PROTOS=$WORKSPACE/voltha-protos/
             make-local voltha-go
             make-local voltha-openolt-adapter
             make-local voltha-openonu-adapter
             make-local ofagent-py
           elif [ "${gerritProject}" = "voltctl" ]; then
             # Set and handle GOPATH and PATH
             export GOPATH=\${GOPATH:-$WORKSPACE/go}
             export PATH=\$PATH:/usr/lib/go-1.12/bin:/usr/local/go/bin:\$GOPATH/bin
             make -C $WORKSPACE/voltctl/ build
           elif ! [[ "${gerritProject}" =~ ^(voltha-helm-charts|voltha-system-tests|kind-voltha)\$ ]]; then
             make-local ${gerritProject}
           fi
           """
      }
    }

    stage('Push Images') {
      steps {
        sh """
           if ! [[ "${gerritProject}" =~ ^(voltha-helm-charts|voltha-system-tests|voltctl|kind-voltha)\$ ]]; then
             docker images | grep citest
             for image in \$(docker images -f "reference=*/*/*citest" --format "{{.Repository}}"); do echo "Pushing \$image to nodes"; kind load docker-image \$image:citest --name ${clusterName} --nodes ${clusterName}-worker,${clusterName}-worker2; done
           fi
           """
      }
    }
    stage('Configuration') {
      steps {
        script {

          if ("${gerritProject}" in ["ofagent-py", "pyvoltha", "voltha-openonu-adapter"])

          if ("${gerritProject}" == "voltha-helm-charts") {
            // TODO support voltha-helm-charts testing
            error("Not supported yet with the new charts")
          }

          def image = ""

          if ("${gerritProject}" == "voltha-go") {
            image = "rw_core"
          } else if ("${gerritProject}" == "ofagent-go") {
            image = "ofagent_go"
          } else if ("${gerritProject}" == "voltha-onos") {
            image = "onos"
          } else if ("${gerritProject}" == "voltha-onos") {
            image = "onos"
          } else if ("${gerritProject}" == "voltha-openolt-adapter") {
            image = "adapter_open_olt"
          } else if ("${gerritProject}" == "voltha-openonu-adapter") {
            image = "adapter_open_onu"
          } else if ("${gerritProject}" == "bbsim") {
            image = "bbsim"
          }

          extraHelmFlags = customImageFlags(image) + " ${extraHelmFlags}"

          println "${extraHelmFlags}"
        }
        // sh '''
        //    // TODO add support for custom helm-charts
        //    if [ "${gerritProject}" = "voltha-helm-charts" ]; then
        //      export CHART_PATH=$WORKSPACE/voltha-helm-charts
        //      export VOLTHA_CHART=\$CHART_PATH/voltha
        //      export VOLTHA_ADAPTER_OPEN_OLT_CHART=\$CHART_PATH/voltha-adapter-openolt
        //      export VOLTHA_ADAPTER_OPEN_ONU_CHART=\$CHART_PATH/voltha-adapter-openonu
        //      helm dep update \$VOLTHA_CHART
        //      helm dep update \$VOLTHA_ADAPTER_OPEN_OLT_CHART
        //      helm dep update \$VOLTHA_ADAPTER_OPEN_ONU_CHART
        //    fi
        //
        //    // TODO add support for voltctl testing
        //    if [ "${gerritProject}" = "voltctl" ]; then
        //      export VOLTCTL_VERSION=$(cat $WORKSPACE/voltctl/VERSION)
        //      cp $WORKSPACE/voltctl/voltctl $WORKSPACE/kind-voltha/bin/voltctl
        //      md5sum $WORKSPACE/kind-voltha/bin/voltctl
        //    fi
        //    '''
      }
    }
    stage('Run Test') {
      steps {
        test_workflow("att")
        test_workflow("dt")
        test_workflow("tt")
      }
    }
  }

  post {
    always {
      sh '''

        # get pods information
        kubectl get pods -o wide --all-namespaces
        kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}"
        helm ls --all-namespaces

         set +e
         cp $WORKSPACE/kind-voltha/install-$NAME.log $WORKSPACE/

         sync
         md5sum $WORKSPACE/kind-voltha/bin/voltctl

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

         extract_errors_go voltha-rw-core > $WORKSPACE/error-report.log || true
         extract_errors_go adapter-open-olt >> $WORKSPACE/error-report.log || true
         extract_errors_python adapter-open-onu >> $WORKSPACE/error-report.log || true
         extract_errors_python voltha-ofagent >> $WORKSPACE/error-report.log || true

         gzip $WORKSPACE/att/onos-voltha-combined.log || true
         gzip $WORKSPACE/dt/onos-voltha-combined.log || true
         gzip $WORKSPACE/tt/onos-voltha-combined.log || true

         '''
         step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/*/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/*/output*.xml',
            outputPath: '.',
            passThreshold: 100,
            reportFileName: 'RobotLogs/*/report*.html',
            unstableThreshold: 0]);
         archiveArtifacts artifacts: '*.log,**/*.log,**/*.gz,*.gz'
    }
  }
}
