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
        rm -rf $WORKSPACE/
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
      steps {
        sh '''
        cd $WORKSPACE/cord/incubator/voltha
        chmod +x env.sh
        source env.sh
        '''
      }
    }

    stage ('Start Provisioning Test') {
      steps {
        sh '''
        cd $WORKSPACE/cord/incubator/voltha/tests/atests/common/
        ./run_robot.sh jenkinstest
        '''
      }
    }

     stage('Publish') {
      steps {
        sh """
           if [ -d RobotLogs ]; then rm -r RobotLogs; fi; mkdir RobotLogs
           cp -r $WORKSPACE/cord/incubator/voltha/jenkinstest/ ./RobotLogs
           """

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
         cp $WORKSPACE/cord/incubator/voltha/tests/atests/common/*.log .
         cp $WORKSPACE/cord/incubator/voltha/tests/atests/common/voltha_test_results/*.log .
         sudo chown cord:cord $WORKSPACE/*log
         '''
         archiveArtifacts artifacts: '*.log'
         step([$class: 'Mailer', notifyEveryUnstableBuild: true, recipients: "gdepatie@northforgeinc.com, kailash@opennetworking.org", sendToIndividuals: false])
    }
  }
}


