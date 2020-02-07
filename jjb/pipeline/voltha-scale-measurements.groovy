/* voltha-scale-measurements pipeline */
pipeline {
  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
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
    stage('checkout') {
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "https://github.com/ciena/kind-voltha.git", ]],
          branches: [[ name: "master", ]],
          extensions: [
            [$class: 'WipeWorkspace'],
            [$class: 'RelativeTargetDirectory', relativeTargetDir: "kind-voltha"],
            [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
          ],
        ])
        script {
          git_tags = sh(script:"cd kind-voltha; git tag -l --points-at HEAD", returnStdout: true).trim()
        }
      }
    }
    stage('start') {
      steps {
        sh '''
          #!/usr/bin/env bash
          set -euo pipefail
          '''
      }
    }

    stage('deploy-voltha') {
      steps {
        sh '''
          cd kind-voltha

          EXTRA_HELM_FLAGS="--set onu=${onuCount},pon=${ponCount}" ./voltha up

          '''
      }
    }
    stage('activate-ONUs') {
      steps {
        sh '''
          export SSHPASS=karaf
          if [ -z "$onuTarget" ]
          then
            echo -e "You need to set the target ONU number\n"
            exit 1
          fi

          voltctl device create -t openolt -H bbsim:50060
          voltctl device enable $(voltctl device list --filter Type~openolt -q)
          SECONDS=0

          # check ONUs reached Active State in VOLTHA
          i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
          until [ $i -eq $onuTarget ]
          do
            echo "$i ONUs ACTIVE of $onuTarget expected (time: $SECONDS)"
            sleep 20
            i=$(voltctl device list | grep -v OLT | grep ACTIVE | wc -l)
          done
          echo "$onuTarget ONUs Activated in $SECONDS seconds (time: $SECONDS)"
          exit 0

        '''
      }
    }
  }
  post {
    cleanup {
      sh '''
        #!/usr/bin/env bash
        set -euo pipefail
        cd $WORKSPACE/kind-voltha

        WAIT_ON_DOWN=y ./voltha down
        cd $WORKSPACE/
        rm -rf kind-voltha/ || true
      '''
    }
  }
}
