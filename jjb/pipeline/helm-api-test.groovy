/* helm-api-test pipeline */

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

    stage('patch') {
      steps {
        sh """
           #!/usr/bin/env bash
           set -eu -o pipefail

           VERSIONFILE="" # file path to file containing version number
           NEW_VERSION="" # version number found in VERSIONFILE
           releaseversion=0

           function read_version {
             if [ -f "VERSION" ]
             then
               NEW_VERSION=\$(head -n1 "VERSION")
               VERSIONFILE="VERSION"
             elif [ -f "package.json" ]
             then
               NEW_VERSION=\$(python -c 'import json,sys;obj=json.load(sys.stdin); print obj["version"]' < package.json)
               VERSIONFILE="package.json"
             else
               echo "ERROR: No versioning file found!"
               exit 1
             fi
           }

           # check if the version is a released version
           function check_if_releaseversion {
             if [[ "\$NEW_VERSION" =~ ^([0-9]+)\\.([0-9]+)\\.([0-9]+)\$ ]]
             then
               echo "Version string '\$NEW_VERSION' in '\$VERSIONFILE' is a SemVer released version!"
               releaseversion=1
             else
               echo "Version string '\$NEW_VERSION' in '\$VERSIONFILE' is not a SemVer released version, skipping."
             fi
           }

           pushd cord
           PROJECT_PATH=\$(xmllint --xpath "string(//project[@name=\\\"${gerritProject}\\\"]/@path)" .repo/manifest.xml)
           repo download "\$PROJECT_PATH" "${gerritChangeNumber}/${gerritPatchsetNumber}"

           pushd \$PROJECT_PATH
           echo "Existing git tags:"
           git tag -n

           read_version
           check_if_releaseversion

           # perform checks if a released version
           if [ "\$releaseversion" -eq "1" ]
           then
             git config --global user.email "apitest@opencord.org"
             git config --global user.name "API Test"

             git tag -a "\$NEW_VERSION" -m "Tagged for api test on Gerrit patchset: ${gerritChangeNumber}"

             echo "Tags including new tag:"
             git tag -n

           fi
           popd
           popd
           """
      }
    }

    stage('prep') {
      parallel {

        stage('images') {
          steps {
            sh '''
               pushd cord/automation-tools/developer
               ./imagebuilder.py -f ../../helm-charts/examples/api-test-images.yaml
               popd
               '''
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

    stage('xos') {
      steps {
        sh '''
           pushd cord/helm-charts
           helm dep up xos-core
           helm install -f examples/image-tag-candidate.yaml -f examples/imagePullPolicy-IfNotPresent.yaml -f examples/api-test-values.yaml xos-core -n xos-core
           sleep 60
           helm status xos-core
           popd
           '''
      }
    }

    stage('test'){
      steps {
        sh '''
           helm test xos-core
           mkdir -p ./RobotLogs;
           cp /tmp/helm_test_xos_core_logs_*/* ./RobotLogs
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
      }
    }
  }

  post {
    always {
      sh '''
         kubectl get pods --all-namespaces
         helm list
         kubectl logs xos-core-api-test
         kubectl delete pod xos-core-api-test
         helm delete --purge xos-core
         minikube delete
         '''
      deleteDir()
    }
  }
}
