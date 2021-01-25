// TODO the 3 stages are very similar, most of the code can be shared

def call(Map config) {

  def defaultConfig = [
    branch: "master",
    gerritProject: "",
    gerritRefspec: "",
    volthaSystemTestsChange: "",
    volthaHelmChartsChange: "",
  ]

  if (!config) {
      config = [:]
  }

  def cfg = defaultConfig + config

  println "Downloading VOLTHA code with the following parameters: ${cfg}."

  stage('Download Patch') {
    // We are always downloading those repos, if the patch under test is in one of those
    // just checkout the patch, no need to clone it again
    if (cfg.gerritProject != 'voltha-system-tests' &&
      cfg.gerritProject != 'voltha-helm-charts' &&
      cfg.gerritProject != '') {
      checkout([
        $class: 'GitSCM',
        userRemoteConfigs: [[
          url: "https://gerrit.opencord.org/${cfg.gerritProject}",
        ]],
        branches: [[ name: "${cfg.branch}", ]],
        extensions: [
          [$class: 'WipeWorkspace'],
          [$class: 'RelativeTargetDirectory', relativeTargetDir: "${cfg.gerritProject}"],
          [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
        ],
      ])
      sh """
        pushd $WORKSPACE/${cfg.gerritProject}
        git fetch https://gerrit.opencord.org/${cfg.gerritProject} ${cfg.gerritRefspec} && git checkout FETCH_HEAD

        echo "Currently on commit: \n"
        git log -1 --oneline
        popd
      """
    }
  }
  stage('Clone voltha-system-tests') {
    checkout([
      $class: 'GitSCM',
      userRemoteConfigs: [[
        url: "https://gerrit.opencord.org/voltha-system-tests",
      ]],
      branches: [[ name: "${cfg.branch}", ]],
      extensions: [
        [$class: 'WipeWorkspace'],
        [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-system-tests"],
        [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
      ],
    ])
    if (cfg.volthaSystemTestsChange != '' && cfg.gerritProject != 'voltha-system-tests') {
      sh """
        cd $WORKSPACE/voltha-system-tests
        git fetch https://gerrit.opencord.org/voltha-system-tests ${cfg.volthaSystemTestsChange} && git checkout FETCH_HEAD
      """
    }
    else if (cfg.gerritProject == 'voltha-system-tests') {
      sh """
        pushd $WORKSPACE/${cfg.gerritProject}
        git fetch https://gerrit.opencord.org/${cfg.gerritProject} ${cfg.gerritRefspec} && git checkout FETCH_HEAD

        echo "Currently on commit: \n"
        git log -1 --oneline
        popd
      """
    }
  }
  stage('Clone voltha-helm-charts') {
    checkout([
      $class: 'GitSCM',
      userRemoteConfigs: [[
        url: "https://gerrit.opencord.org/voltha-helm-charts",
      ]],
      branches: [[ name: "master", ]],
      extensions: [
        [$class: 'WipeWorkspace'],
        [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-helm-charts"],
        [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
      ],
    ])
    if (cfg.volthaHelmChartsChange != '' && cfg.gerritProject != 'voltha-helm-charts') {
      sh """
        cd $WORKSPACE/voltha-helm-charts
        git fetch https://gerrit.opencord.org/voltha-helm-charts ${cfg.volthaHelmChartsChange} && git checkout FETCH_HEAD
      """
    }
    else if (cfg.gerritProject == 'voltha-helm-charts') {
      sh """
        pushd $WORKSPACE/${cfg.gerritProject}
        git fetch https://gerrit.opencord.org/${cfg.gerritProject} ${cfg.gerritRefspec} && git checkout FETCH_HEAD

        echo "Currently on commit: \n"
        git log -1 --oneline
        popd
      """
    }
  }
}
