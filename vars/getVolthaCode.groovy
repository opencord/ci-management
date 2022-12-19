#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getIam(String func)
{
    String src = 'vars/getVolthaCode.groovy'
    String iam = [src, func].join('::')
    return iam
}

// TODO the 3 stages are very similar, most of the code can be shared

def wrapped(Map config)
{
    def defaultConfig = [
	branch: "master",
	gerritProject: "",
	gerritRefspec: "",
	volthaSystemTestsChange: "",
	volthaHelmChartsChange: "",
    ]
    
    def cfg = defaultConfig + config

    println "Downloading VOLTHA code with the following parameters: ${cfg}."

    stage('Download Patch')
    {
	frequent_repos = [
	    '',
	    'voltha-system-tests',
	    'voltha-helm-charts',
	]

	// We are always downloading those repos, if the patch under test is in one of those
	// just checkout the patch, no need to clone it again
	if ( !(cfg.gerritProject in frequent_repos))
	{
	    repo_project = "https://gerrit.opencord.org/${cfg.gerritProject}"
		
	    checkout([
		$class: 'GitSCM',
		userRemoteConfigs: [[ url:repo_project ]],
		branches: [[ name: "${cfg.branch}", ]],
		extensions: [
		    [$class: 'WipeWorkspace'],
		    [$class: 'RelativeTargetDirectory', relativeTargetDir: "${cfg.gerritProject}"],
		    [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
		],
	    ])

	    sh """
        pushd $WORKSPACE/${cfg.gerritProject}
        git fetch "$repo_project" ${cfg.gerritRefspec} && git checkout FETCH_HEAD

        echo "Currently on commit: \n"
        git log -1 --oneline
        popd
      """
	}
    }

    stage('Clone voltha-system-tests')
    {	
	repo_vst = 'https://gerrit.opencord.org/voltha-system-tests'

	checkout([
	    $class: 'GitSCM',
	    userRemoteConfigs: [[ url:repo_vst ]],
	    branches: [[ name: "${cfg.branch}", ]],
	    extensions: [
		[$class: 'WipeWorkspace'],
		[$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-system-tests"],
		[$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
	    ],
	])

	if (cfg.volthaSystemTestsChange != '' && cfg.gerritProject != 'voltha-system-tests')
	{
	    sh """
        cd "$WORKSPACE/voltha-system-tests"
        git fetch "${repo_vst}" ${cfg.volthaSystemTestsChange} && git checkout FETCH_HEAD
      """
	}
	else if (cfg.gerritProject == 'voltha-system-tests') {
	    sh """
        pushd "$WORKSPACE/${cfg.gerritProject}"
        git fetch https://gerrit.opencord.org/${cfg.gerritProject} ${cfg.gerritRefspec} && git checkout FETCH_HEAD

        echo "Currently on commit: \n"
        git log -1 --oneline
        popd
      """
	}
    }

    stage('Clone voltha-helm-charts')
    {
	repo_vhc = 'https://gerrit.opencord.org/voltha-helm-charts'

	checkout([
	    $class: 'GitSCM',
	    userRemoteConfigs: [[ url:repo_vhc ]],
	    branches: [[ name: "${cfg.branch}", ]],
	    extensions: [
		[$class: 'WipeWorkspace'],
		[$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-helm-charts"],
		[$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
	    ],
	])

	if (cfg.volthaHelmChartsChange != '' && cfg.gerritProject != 'voltha-helm-charts') {
	    sh """
        cd "$WORKSPACE/voltha-helm-charts"
        git fetch "$repo_vhc" ${cfg.volthaHelmChartsChange} && git checkout FETCH_HEAD
      """
	}
	else if (cfg.gerritProject == 'voltha-helm-charts') {
	    sh """
        pushd "$WORKSPACE/${cfg.gerritProject}"
        git fetch "https://gerrit.opencord.org/${cfg.gerritProject}" ${cfg.gerritRefspec} && git checkout FETCH_HEAD

        echo "Currently on commit: \n"
        git log -1 --oneline
        popd
      """
	}
    }
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(Map config)
{
    String iam = getIam('main')
    Boolean debug = false

    if (debug)
    {
	println("** ${iam}: ENTER")
    }

    if (!config) {
        config = [:]
    }

    try
    {
	wrapped(config)
    }
    catch (Exception err)
    {
	println("** ${iam}: EXCEPTION ${err}")
	throw err
    }
    finally
    {
	if (debug)
	{
	    println("** ${iam}: LEAVE")
	}
    }

    return
}
