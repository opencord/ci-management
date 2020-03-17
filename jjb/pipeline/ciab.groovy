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

    stage ("Checkout Repos"){
      steps {
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "https://gerrit.opencord.org/automation-tools" ]],
          extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "automation-tools"]],
          ],
        )
        checkout([
          $class: 'GitSCM',
          userRemoteConfigs: [[ url: "https://gerrit.opencord.org/helm-charts" ]],
          extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "helm-charts"]],
          ],
        )
        sh label: 'Fetch helm-charts Gerrit Change', script: '''
          mkdir cord/
          mv helm-charts/ cord/
          cd cord/helm-charts/
          git review -d ${params.GERRIT_CHANGE_NUMBER}
          '''
      }
    }

    stage ("Run COMAC-in-a-box"){
      steps {
        sh label: 'Run Makefile', script: '''
          cd automation-tools/comac-in-a-box/
          make
          make test
          '''
      }
    }
  }
}
