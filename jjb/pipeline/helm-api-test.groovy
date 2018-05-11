/* helm-api-test pipeline */

pipeline {

  parameters {
    string(name:'executorNode', defaultValue:'invalid', description:'Name of the Jenkins node to run the job on')
    string(name:'manifestUrl', defaultValue:'invalid', description:'URL to the repo manifest')
    string(name:'manifestBranch', defaultValue:'master', description:'Name of the repo branch to use')
  }

  /* no label, executor is determined by JJB */
  agent {
    label '${params.executorNode}'
  }

  stages {

    stage('checkout') {
      steps {
        checkout(changelog: false, \
          poll: false,
          scm: [$class: 'RepoScm', \
            manifestRepositoryUrl: '${params.manifestUrl}', \
            manifestBranch: '${params.manifestBranch}', \
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

    stage('prep') {
      parallel {

        stage('images') {
          steps {
            sh 'cd cord/build; ./scripts/imagebuilder.py -f helm-charts/examples/test-images.yaml'

          }
        }

        stage('minikube') {
          steps {
            /* see https://github.com/kubernetes/minikube/#linux-continuous-integration-without-vm-support */
            sh '''
               export MINIKUBE_WANTUPDATENOTIFICATION=false;
               export MINIKUBE_WANTREPORTERRORPROMPT=false;
               export CHANGE_MINIKUBE_NONE_USER=true;
               export MINIKUBE_HOME=$HOME;
               mkdir -p $HOME/.kube || true;
               touch $HOME/.kube/config;
               export KUBECONFIG=$HOME/.kube/config;
               minikube start --vm-driver=none;

               chown -R $USER $HOME/.minikube;
               chgrp -R $(id -g) $HOME/.minikube;

               for i in {1..150}; do # timeout for 5 minutes
                   ./kubectl get po &> /dev/null
                   if [ $? -ne 1 ]; then
                      break
                  fi
                  sleep 2
               done
               '''
          }
        }
      }
    }

    stage('helm') {
      steps {
        sh 'helm init && sleep 60'
      }
    }

    stage('xos') {
      steps {
        sh '''
           cd cord/build/helm-charts;
           helm dep up xos-core;
           helm install -f examples/test-values.yaml -f examples/candidate-tag-values.yaml xos-core -n xos-core;
           sleep 60
           '''
      }
    }

    stage('test'){
      steps {
        sh 'helm test xos-core'
        sh 'kubectl logs xos-core-api-test'
      }
      post {
        always {
          archive '/tmp/helm_test_xos_core_logs_*/**'

        }
      }
    }
  }

  post {
    always {
      sh 'kubectl delete pod xos-core-api-test'
      sh 'helm delete --purge xos-core'
      deleteDir()
    }
  }
}
