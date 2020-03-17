/* comac-in-a-box build+test
   steps taken from https://guide.opencord.org/profiles/comac/install/ciab.html */

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }

  options {
    timeout(time: 1, unit: 'HOURS')
  }

  stages {
    stage ("Clean workspace"){
      steps {
        sh 'rm -rf *'
      }
    }

    stage ("Fetch Helm-Charts Changes"){
      steps {
        sh label: 'Fetch helm-charts Gerrit Changes', script: '''
          ssh comac@192.168.122.56 '
              cd cord/helm-charts/
              git review -d ${params.GERRIT_CHANGE_NUMBER} | true
              '
          '''
      }
    }

    stage ("Run COMAC-in-a-box"){
      steps {
        sh label: 'Run Makefile', script: '''
        ssh comac@192.168.122.56 '
              cd automation-tools/comac-in-a-box/
              make
              make test
              '
          '''
      }
    }
  }
}
