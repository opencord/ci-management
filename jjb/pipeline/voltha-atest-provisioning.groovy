/* voltha-atest-provisioning pipeline */

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.executorNode}"
  }

  stages {

    stage ('Clean up') {
      steps {
        sh '''
        sudo rm -rf *
        sudo rm -rf /home/cord/cord*
        '''
      }
    }

    stage('Voltha Repo') {
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

    stage ('Build Voltha and ONOS') {
      when { expression { return params.BuildVoltha } }
      steps {
        sh '''
        sudo service docker restart
        cd $WORKSPACE/cord/incubator/voltha
        repo download "${GERRIT_PROJECT}" "${gerritChangeNumber}/${gerritPatchsetNumber}"
        chmod +x env.sh
        source env.sh
        make fetch
        make clean
        make build
        '''
      }
    }

    stage ('Build BBSIM') {
      when { expression { return params.BuildBbsim } }
      steps {
        sh '''
        sudo service docker restart
        cd $WORKSPACE/cord/incubator/voltha-bbsim
        repo download ${gerritProject} "${gerritChangeNumber}/${gerritPatchsetNumber}"
        make docker
        docker images | grep bbsim
        '''
      }
    }

    stage ('Start Voltha Test Suite') {
      steps {
        sh '''
        cd $WORKSPACE/cord/incubator/voltha/tests/atests/common/
        ./run_robot.sh jenkinstest ${params.adapter} || true
        '''
      }
    }

     stage('Publish') {
      steps {
        sh """
           if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
           cp -r $WORKSPACE/cord/incubator/voltha/jenkinstest/ ./RobotLogs
           cp -r $WORKSPACE/cord/incubator/voltha/jenkinstest/voltha_test_results/*.log $WORKSPACE/
           """

        step([$class: 'RobotPublisher',
            disableArchiveOutput: false,
            logFileName: 'RobotLogs/jenkinstest/log*.html',
            otherFiles: '',
            outputFileName: 'RobotLogs/jenkinstest/output*.xml',
            outputPath: '.',
            passThreshold: 100,
            reportFileName: 'RobotLogs/jenkinstest/report*.html',
            unstableThreshold: 0]);
      }
    }
  }

  post {
    always {
         archiveArtifacts artifacts: '*.log'
         step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "gdepatie@northforgeinc.com, kailash@opennetworking.org", sendToIndividuals: false])
    }
  }
}


