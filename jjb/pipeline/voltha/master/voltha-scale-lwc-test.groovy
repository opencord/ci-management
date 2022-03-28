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

def lwc_helm_chart_path="/home/jenkins/Radisys_LWC_helm_charts"
def value_file="/home/jenkins/lwc-values.yaml"
def pon_count=16
def onu_count=32
def workflow="dt"

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
              helm del -n infra voltha-infra || true
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
    stage('Download Code') {
      steps {
        getVolthaCode([
          branch: "${release}",
          volthaSystemTestsChange: "${volthaSystemTestsChange}",
          volthaHelmChartsChange: "${volthaHelmChartsChange}",
        ])
      }
    }
    stage('Deploy Voltha') {
      steps {
        timeout(time: 5, unit: 'MINUTES') {
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
        timeout(time: 10, unit: 'MINUTES') {
          sh """
          cd /home/jenkins/Radisys_LWC_helm_charts

          helm dep update ${lwc_helm_chart_path}/voltha-infra
          helm upgrade --install --create-namespace -n infra voltha-infra ${lwc_helm_chart_path}/voltha-infra -f examples/${workflow}-values.yaml \
            -f ${value_file} --wait

          # helm dep update ${lwc_helm_chart_path}/voltha-stack
          helm upgrade --install --create-namespace -n voltha1 voltha1 onf/voltha-stack \
          --set voltha.ingress.enabled=true --set voltha.ingress.enableVirtualHosts=true --set voltha.fullHostnameOverride=voltha.scale1.dev \
          -f ${value_file} --wait

          helm upgrade --install -n voltha1 bbsim0 onf/bbsim --set olt_id=10 -f examples/${workflow}-values.yaml --set pon=${pon_count},onu=${onu_count} --version 4.6.0 --set oltRebootDelay=5 --wait
          """
        }
      }
    }
    stage('Load MIB Template') {
      when {
        expression {
          return params.withMibTemplate
        }
      }
      steps {
        sh """
        # load MIB template
        wget ${mibTemplateUrl} -O mibTemplate.json
        cat mibTemplate.json | kubectl exec -it -n infra \$(kubectl get pods -n infra |grep etcd-0 | awk 'NR==1{print \$1}') -- etcdctl put service/voltha/omci_mibs/go_templates/BBSM/12345123451234512345/BBSM_IMG_00001
        """
      }
    }
    stage('Run Test') {
      steps {
        sh """
          mkdir -p $WORKSPACE/RobotLogs
          cd $WORKSPACE/voltha-system-tests
          make vst_venv

          daemonize -E JENKINS_NODE_COOKIE="dontKillMe" /usr/local/bin/kubectl port-forward -n infra svc/lwc 8182:8181 --address 0.0.0.0

          source ./vst_venv/bin/activate
          robot -d $WORKSPACE/RobotLogs \
          --exitonfailure \
          -v pon:${pon_count} -v onu:${onu_count} \
          tests/scale/Voltha_Scale_Tests_lwc.robot

          python tests/scale/collect-result.py -r $WORKSPACE/RobotLogs/output.xml -p $WORKSPACE/plots > $WORKSPACE/execution-time.txt || true
          cat $WORKSPACE/execution-time.txt
        """
      }
    }
  }
  post {
    always {
      stopComponentsLogs()
      script {
        try {
          step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: '**/log*.html',
            otherFiles: '',
            outputFileName: '**/output*.xml',
            outputPath: 'RobotLogs',
            passThreshold: 100,
            reportFileName: '**/report*.html',
            onlyCritical: true,
            unstableThreshold: 0]);
        } catch (Exception e) {
            println "Cannot archive Robot Logs: ${e.toString()}"
        }
      }
      getPodsInfo("$LOG_FOLDER")
      archiveArtifacts artifacts: 'execution-time.txt,logs/*,logs/pprof/*,RobotLogs/**/*,plots/*,etcd-metrics/*'
    }
  }
}
