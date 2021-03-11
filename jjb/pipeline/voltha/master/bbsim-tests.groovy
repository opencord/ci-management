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

// NOTE we are importing the library even if it's global so that it's
// easier to change the keywords during a replay
library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://gerrit.opencord.org/ci-management.git'
])

def customImageFlags(image) {
  return "--set images.${image}.tag=citest,images.${image}.pullPolicy=Never "
}

def test_workflow(name) {
  stage('Deploy - '+ name + ' workflow') {
      def extraHelmFlags = "${extraHelmFlags} --set global.log_level=DEBUG,onu=1,pon=1 "

      if (gerritProject != "") {
        extraHelmFlags = extraHelmFlags + customImageFlags("${gerritProject}")
      }

      def localCharts = false
      if (gerritProject == "voltha-helm-charts") {
        localCharts = true
      }

      volthaDeploy([workflow: name, extraHelmFlags: extraHelmFlags, localCharts: localCharts])
      // start logging
      sh """
      mkdir -p $WORKSPACE/${name}
      _TAG=kail-${name} kail -n infra -n voltha > $WORKSPACE/${name}/onos-voltha-combined.log &
      """
      // forward ONOS and VOLTHA ports
      sh """
      _TAG=onos-port-forward kubectl port-forward --address 0.0.0.0 -n infra svc/voltha-infra-onos-classic-hs 8101:8101&
      _TAG=onos-port-forward kubectl port-forward --address 0.0.0.0 -n infra svc/voltha-infra-onos-classic-hs 8181:8181&
      _TAG=voltha-port-forward kubectl port-forward --address 0.0.0.0 -n voltha svc/voltha-voltha-api 55555:55555&
      """
  }
  stage('Test VOLTHA - '+ name + ' workflow') {
      sh """
      ROBOT_LOGS_DIR="$WORKSPACE/RobotLogs/${name.toUpperCase()}Workflow"
      mkdir -p \$ROBOT_LOGS_DIR
      export ROBOT_MISC_ARGS="-d \$ROBOT_LOGS_DIR -e PowerSwitch"

      # By default, all tests tagged 'sanity' are run.  This covers basic functionality
      # like running through the ATT workflow for a single subscriber.
      export TARGET=sanity-kind-${name}

      # If the Gerrit comment contains a line with "functional tests" then run the full
      # functional test suite.  This covers tests tagged either 'sanity' or 'functional'.
      # Note: Gerrit comment text will be prefixed by "Patch set n:" and a blank line
      REGEX="functional tests"
      if [[ "\$GERRIT_EVENT_COMMENT_TEXT" =~ \$REGEX ]]; then
        export TARGET=functional-single-kind-${name}
      fi

      if [[ "${gerritProject}" == "bbsim" ]]; then
        echo "Running BBSim specific Tests"
        export TARGET=sanity-bbsim-${name}
      fi

      export VOLTCONFIG=$HOME/.volt/config
      export KUBECONFIG=$HOME/.kube/config

      # Run the specified tests
      make -C $WORKSPACE/voltha-system-tests \$TARGET || true
      """
      // stop logging
      sh """
        P_IDS="\$(ps e -ww -A | grep "_TAG=kail-${name}" | grep -v grep | awk '{print \$1}')"
        if [ -n "\$P_IDS" ]; then
          echo \$P_IDS
          for P_ID in \$P_IDS; do
            kill -9 \$P_ID
          done
        fi
      """
      // remove port-forwarding
      sh """
        # remove orphaned port-forward from different namespaces
        ps aux | grep port-forw | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9
      """
      // collect pod details
      get_pods_info("$WORKSPACE/${name}")
      helmTeardown(['infra', 'voltha'])
  }
}

def get_pods_info(dest) {
  // collect pod details, this is here in case of failure
  sh """
  mkdir -p ${dest}
  kubectl get pods --all-namespaces -o wide > ${dest}/pods.txt || true
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq | tee ${dest}/pod-images.txt || true
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq | tee ${dest}/pod-imagesId.txt || true
  kubectl describe pods --all-namespaces -l app.kubernetes.io/part-of=voltha > ${dest}/pods-describe.txt
  helm ls --all-namespaces > ${dest}/helm-charts.txt
  """
}

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
    timeout(time: 35, unit: 'MINUTES')
  }
  environment {
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin"
    KUBECONFIG="$HOME/.kube/kind-config-${clusterName}"
  }

  stages{
    stage('Download Code') {
      steps {
        getVolthaCode([
          branch: "${branch}",
          gerritProject: "${gerritProject}",
          gerritRefspec: "${gerritRefspec}",
          volthaSystemTestsChange: "${volthaSystemTestsChange}",
          volthaHelmChartsChange: "${volthaHelmChartsChange}",
        ])
      }
    }
    stage('Build patch') {
      steps {
        // NOTE that the correct patch has already been checked out
        // during the getVolthaCode step
        buildVolthaComponent("${gerritProject}")
      }
    }
    stage('Create K8s Cluster') {
      steps {
        createKubernetesCluster([nodes: 3])
      }
    }
    stage('Load image in kind nodes') {
      steps {
        loadToKind()
      }
    }
    stage('Replace voltctl') {
      // if the project is voltctl override the downloaded one with the built one
      when {
        expression {
          return gerritProject == "voltctl"
        }
      }
      steps{
        sh """
        mv `ls $WORKSPACE/voltctl/release/voltctl-*-linux-amd*` $WORKSPACE/bin/voltctl
        chmod +x $WORKSPACE/bin/voltctl
        """
      }
    }
    stage('Run Test') {
      steps {
        timeout(time: 30, unit: 'MINUTES') {
          test_workflow("att")
          test_workflow("dt")
          test_workflow("tt")
        }
      }
    }
  }

  post {
    aborted {
      get_pods_info("$WORKSPACE/failed")
    }
    failure {
      get_pods_info("$WORKSPACE/failed")
    }
    always {
      sh '''
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
      archiveArtifacts artifacts: '*.log,**/*.log,**/*.gz,*.gz,*.txt,**/*.txt'
    }
  }
}
