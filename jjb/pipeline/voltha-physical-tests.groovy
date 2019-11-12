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

// deploy VOLTHA built from patchset on a physical pod and run e2e test
// uses kind-voltha to deploy voltha-2.X

node {
    // Need this so that deployment_config has global scope when it's read later
    deployment_config = null
    localDeploymentConfigFile = null
    localKindVolthaValuesFile = null
    localSadisConfigFile = null
}

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }
  options {
      timeout(time: 60, unit: 'MINUTES')
  }

  environment {
    KUBECONFIG="$HOME/.kube/kind-config-voltha-minimal"
    VOLTCONFIG="$HOME/.volt/config-minimal"
    PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$WORKSPACE/kind-voltha/bin"
    TYPE="minimal"
    FANCY=0
    //VOL-2194 ONOS SSH and REST ports hardcoded to 30115/30120 in tests
    ONOS_SSH_PORT=30115
    ONOS_API_PORT=30120
  }

  stages {
    stage ('Initialize') {
      steps {
        sh returnStdout: true, script: """
        test -e $WORKSPACE/kind-voltha/voltha && cd $WORKSPACE/kind-voltha && ./voltha down
        cd $WORKSPACE
        rm -rf $WORKSPACE/*
        """
        script {
          if (env.configRepo && ! env.localConfigDir) {
            env.localConfigDir = "$WORKSPACE"
            sh returnStdout: true, script: "git clone -b master ${cordRepoUrl}/${configRepo}"
          }
          localDeploymentConfigFile = "${env.localConfigDir}/${params.deploymentConfigFile}"
          localKindVolthaValuesFile = "${env.localConfigDir}/${params.kindVolthaValuesFile}"
          localSadisConfigFile = "${env.localConfigDir}/${params.sadisConfigFile}"
          if ( ! params.withPatchset ) {
            sh returnStdout: false, script: """
            mkdir -p voltha
            cd voltha
            git clone -b ${branch} ${cordRepoUrl}/voltha-system-tests
            """
          }
          try {
            deployment_config = readYaml file: "${localDeploymentConfigFile}"
          } catch (err) {
            echo "Error reading ${localDeploymentConfigFile}"
            throw err
          }
          sh returnStdout: false, script: """
          if [ ! -e ${localKindVolthaValuesFile} ]; then echo "${localKindVolthaValuesFile} not found"; exit 1; fi
          if [ ! -e ${localSadisConfigFile} ]; then echo "${localSadisConfigFile} not found"; exit 1; fi
          """
        }
      }
    }

    stage('Get Patch') {
      when {
        expression { params.withPatchset }
      }
      steps {
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
        sh returnStdout: false, script: """
        cd voltha
        PROJECT_PATH=\$(xmllint --xpath "string(//project[@name=\\\"${gerritProject}\\\"]/@path)" .repo/manifest.xml)
        repo download "\$PROJECT_PATH" "${gerritChangeNumber}/${gerritPatchsetNumber}"
        """
      }
    }

    stage('Create KinD Cluster') {
      steps {
        sh returnStdout: false, script: """
        git clone https://github.com/ciena/kind-voltha.git
        cd kind-voltha/
        JUST_K8S=y ./voltha up
        """
      }
    }

    stage('Build and Push Images') {
      when {
        expression { params.withPatchset }
      }
      steps {
        sh returnStdout: false, script: """
        if ! [[ "${gerritProject}" =~ ^(voltha-system-tests)\$ ]]; then
          make -C $WORKSPACE/voltha/${gerritProject} DOCKER_REPOSITORY=voltha/ DOCKER_TAG=citest docker-build
          docker images | grep citest
          for image in \$(docker images -f "reference=*/*citest" --format "{{.Repository}}")
          do
            echo "Pushing \$image to nodes"
            kind load docker-image \$image:citest --name voltha-\$TYPE --nodes voltha-\$TYPE-worker,voltha-\$TYPE-worker2
            docker rmi \$image:citest \$image:latest || true
          done
        fi
        """
      }
    }

    stage('Deploy Voltha') {
      environment {
        WITH_SIM_ADAPTERS="n"
        WITH_RADIUS="y"
        DEPLOY_K8S="n"
        VOLTHA_LOG_LEVEL="debug"
      }
      steps {
        script {
          if ( params.withPatchset ) {
            sh returnStdout: false, script: """
            export EXTRA_HELM_FLAGS='-f ${localKindVolthaValuesFile} '

            IMAGES=""
            if [ "${gerritProject}" = "voltha-go" ]; then
                IMAGES="cli ofagent rw_core ro_core "
            elif [ "${gerritProject}" = "voltha-openolt-adapter" ]; then
                IMAGES="adapter_open_olt "
            elif [ "${gerritProject}" = "voltha-openonu-adapter" ]; then
                IMAGES="adapter_open_onu "
            elif [ "${gerritProject}" = "voltha-api-server" ]; then
                IMAGES="afrouter afrouterd "
            else
                echo "No images to push"
            fi

            for I in \$IMAGES
            do
                EXTRA_HELM_FLAGS+="--set images.\$I.tag=citest,images.\$I.pullPolicy=Never "
            done

            cd $WORKSPACE/kind-voltha/
            echo \$EXTRA_HELM_FLAGS
            ./voltha up
            """
          } else {
            sh returnStdout: false, script: """
            export EXTRA_HELM_FLAGS='-f ${localKindVolthaValuesFile} '
            cd $WORKSPACE/kind-voltha/
            echo \$EXTRA_HELM_FLAGS
            ./voltha up
            """
          }
        }
      }
    }

    stage('Push Tech-Profile') {
      when {
        expression { params.profile != "Default" }
      }
      steps {
        sh returnStdout: false, script: """
        etcd_container=\$(kubectl get pods -n voltha | grep voltha-etcd-cluster | awk 'NR==1{print \$1}')
        kubectl cp $WORKSPACE/voltha/voltha-system-tests/tests/data/TechProfile-${profile}.json voltha/\$etcd_container:/tmp/flexpod.json
        kubectl exec -it \$etcd_container -n voltha -- /bin/sh -c 'cat /tmp/flexpod.json | ETCDCTL_API=3 etcdctl put service/voltha/technology_profiles/xgspon/64'
        """
      }
    }

    stage('Push Sadis-config') {
      steps {
        sh returnStdout: false, script: """
        curl -sSL --user karaf:karaf -X POST -H Content-Type:application/json http://${deployment_config.nodes[0].ip}:$ONOS_API_PORT/onos/v1/network/configuration --data @${localSadisConfigFile}
        """
      }
    }

    stage('Reinstall OLT software') {
      when {
        expression { params.reinstallOlt }
      }
      steps {
        script {
          deployment_config.olts.each { olt ->
            sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service bal_core_dist stop' || true"
            sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service bal_core_dist stop' || true"
            sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'dpkg --remove asfvolt16 && dpkg --purge asfvolt16'"
            waitUntil {
              olt_sw_present = sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'dpkg --list | grep asfvolt16 | wc -l'"
              return olt_sw_present.toInteger() == 0
            }
            sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'dpkg --install ${oltDebVersion}'"
            waitUntil {
              olt_sw_present = sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'dpkg --list | grep asfvolt16 | wc -l'"
              return olt_sw_present.toInteger() == 1
            }
            if ( olt.fortygig ) {
              // If the OLT is connected to a 40G switch interface, set the NNI port to be downgraded
              sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'echo port ce128 sp=40000 >> /broadcom/qax.soc ; /opt/bcm68620/svk_init.sh'"
            }
          }
        }
      }
    }

    stage('Restart OLT processes') {
      steps {
        script {
          deployment_config.olts.each { olt ->
            sh returnStdout: true, script: """
            ssh-keyscan -H ${olt.ip} >> ~/.ssh/known_hosts
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service bal_core_dist stop' || true
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service openolt stop' || true
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'rm -f /var/log/bal_core_dist.log /var/log/openolt.log'
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service bal_core_dist start &'
            sleep 5
            sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'service openolt start &'
            """
            waitUntil {
              onu_discovered = sh returnStdout: true, script: "sshpass -p ${olt.pass} ssh -l ${olt.user} ${olt.ip} 'grep \"onu discover indication\" /var/log/openolt.log | wc -l'"
              return onu_discovered.toInteger() > 0
            }
          }
        }
      }
    }

    stage('Run E2E Tests') {
      environment {
        ROBOT_VAR_FILE="${localDeploymentConfigFile}"
        ROBOT_MISC_ARGS="--removekeywords wuks -d $WORKSPACE/RobotLogs"
      }
      steps {
        sh returnStdout: false, script: """
        cd voltha
        git clone -b ${branch} ${cordRepoUrl}/cord-tester
        git clone -b ${branch} ${cordRepoUrl}/voltha # VOL-2104 recommends we get rid of this
        mkdir -p $WORKSPACE/RobotLogs
        make -C $WORKSPACE/voltha/voltha-system-tests voltha-podtest || true
        """
      }
    }
  }

  post {
    always {
      sh returnStdout: true, script: """
      set +e
      cp kind-voltha/install-minimal.log $WORKSPACE/
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
        elif [[ \$pod == "bbsim-"* ]]; then
          kubectl logs \$pod -n voltha -p > $WORKSPACE/\$pod.log;
        else
          kubectl logs \$pod -n voltha > $WORKSPACE/\$pod.log;
        fi
      done
      """
      step([$class: 'RobotPublisher',
        disableArchiveOutput: false,
        logFileName: 'RobotLogs/log*.html',
        otherFiles: '',
        outputFileName: 'RobotLogs/output*.xml',
        outputPath: '.',
        passThreshold: 80,
        reportFileName: 'RobotLogs/report*.html',
        unstableThreshold: 0]);
      archiveArtifacts artifacts: '*.log'
    }
  }
}
