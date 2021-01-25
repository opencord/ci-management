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

def customImageFlags(image) {
  return "--set images.${image}.tag=citest,images.${image}.pullPolicy=Never "
}

def test_workflow(name) {
  stage('Deploy and test VOLTHA - '+ name + ' workflow') {
      def extraHelmFlags = "--set global.log_level=DEBUG,onu=1,pon=1 "

      extraHelmFlags = extraHelmFlags + customImageFlags("${gerritProject}")

      volthaDeploy([workflow: name, extraHelmFlags: extraHelmFlags])
      // start logging
      sh """
      mkdir -p $WORKSPACE/att
      _TAG=kail-${name} kail -n default > $WORKSPACE/${name}/onos-voltha-combined.log &
      """
      // forward ONOS and VOLTHA ports
      sh """
      _TAG=onos-port-forward kubectl port-forward --address 0.0.0.0 -n infra svc/voltha-infra-onos-classic-hs 8101:8101&
      _TAG=onos-port-forward kubectl port-forward --address 0.0.0.0 -n infra svc/voltha-infra-onos-classic-hs 8181:8181&
      _TAG=voltha-port-forward kubectl port-forward --address 0.0.0.0 -n voltha svc/voltha-voltha-api 55555:55555&
      """
      // run test
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
      if [[ "\$GERRIT_EVENT_COMMENT_TEXT" =~ \$REGEX ]]; then
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
      sh """
      kubectl get pods --all-namespaces -o wide > \$WORKSPACE/${name}/pods.txt || true
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq | tee \$WORKSPACE/att/pod-images.txt || true
      kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq | tee \$WORKSPACE/att/pod-imagesId.txt || true
      """
      helmTeardown(['infra', 'voltha'])
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
          volthaSystemTestsChange: "",
          volthaHelmChartsChange: "",
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
      echo "done!"
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
