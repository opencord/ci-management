// Copyright 2019-present Open Networking Foundation
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

// deploy VOLTHA and performs a scale test with the LWC controller

library identifier: 'cord-jenkins-libraries@master',
    retriever: modernSCM([
      $class: 'GitSCMSource',
      remote: 'https://gerrit.opencord.org/ci-management.git'
])

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 60, unit: 'MINUTES')
  }
  environment {
    JENKINS_NODE_COOKIE="dontKillMe" // do not kill processes after the build is done
    KUBECONFIG="$HOME/.kube/config"
    VOLTCONFIG="$HOME/.volt/config"
    SSHPASS="karaf"
    VOLTHA_LOG_LEVEL="${logLevel}"
    NUM_OF_BBSIM="${olts}"
    NUM_OF_OPENONU="${openonuAdapterReplicas}"
    NUM_OF_ONOS="${onosReplicas}"
    NUM_OF_ATOMIX="${atomixReplicas}"
    EXTRA_HELM_FLAGS=" "
    LOG_FOLDER="$WORKSPACE/logs"
    GERRIT_PROJECT="${GERRIT_PROJECT}"
    PATH="$PATH:$WORKSPACE/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin"
  }

  stages {
    stage ('Cleanup') {
      steps {
        script {
          try {
            timeout(time: 5, unit: 'MINUTES') {
              sh returnStdout: false, script: '''
              cd $WORKSPACE
              rm -rf $WORKSPACE/*
              '''
              // removing the voltha-infra chart first
              // if we don't ONOS might get stuck because of all the events when BBSim goes down
              sh returnStdout: false, script: '''
              set +x
              helm del voltha-infra || true
              echo -ne "\nWaiting for ONOS to be removed..."
              onos=$(kubectl get pod -n default -l app=onos-classic --no-headers | wc -l)
              while [[ $onos != 0 ]]; do
                onos=$(kubectl get pod -n default -l app=onos-classic --no-headers | wc -l)
                sleep 5
                echo -ne "."
              done
              '''
            }
          } catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
            // if we have a timeout in the Cleanup fase most likely ONOS got stuck somewhere, thuse force remove the pods
            sh '''
              kubectl get pods | grep Terminating | awk '{print $1}' | xargs kubectl delete pod --force --grace-period=0
            '''
          }
          timeout(time: 10, unit: 'MINUTES') {
            script {
              helmTeardown(["default", "voltha1", "voltha-infra"])
            }
            sh returnStdout: false, script: '''
              helm repo add onf https://charts.opencord.org
              helm repo update

              # remove all persistent volume claims
              kubectl delete pvc --all-namespaces --all
              PVCS=\$(kubectl get pvc --all-namespaces --no-headers | wc -l)
              while [[ \$PVCS != 0 ]]; do
                sleep 5
                PVCS=\$(kubectl get pvc --all-namespaces --no-headers | wc -l)
              done

              # remove orphaned port-forward from different namespaces
              ps aux | grep port-forw | grep -v grep | awk '{print $2}' | xargs --no-run-if-empty kill -9 || true
            '''
          }
        }
      }
    }
    stage('Deploy Voltha') {
      steps {
        timeout(time: 10, unit: 'MINUTES') {
          installVoltctl("${release}")
          script {
            startComponentsLogs([
              appsToLog: [
                'app.kubernetes.io/name=etcd',
                'app.kubernetes.io/name=kafka',
                'app=lwc',
                'app=adapter-open-onu',
                'app=adapter-open-olt',
                'app=rw-core',
                'app=bbsim',
              ]
            ])
          }
        }
      }
    }
  }
}
