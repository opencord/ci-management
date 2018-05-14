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

    stage('prep') {
      parallel {

        stage('images') {
          steps {
            sh 'cd cord/build; ./scripts/imagebuilder.py -f ../helm-charts/examples/test-images.yaml'

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
           cd cord/helm-charts
           helm dep up xos-core
           helm install -f examples/test-values.yaml -f examples/candidate-tag-values.yaml xos-core -n xos-core
           sleep 60
           '''
      }
    }

    stage('test'){
      steps {
        sh '''
           helm test xos-core
           kubectl logs xos-core-api-test
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
         kubectl delete pod xos-core-api-test
         helm delete --purge xos-core
         '''
      deleteDir()
    }
  }
}
