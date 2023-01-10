#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Install the voltctl command by branch name "voltha-xx"
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getIam(String func)
{
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/installVoltctl.groovy'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def process(String branch) {

    String iam = 'vars/installVoltctl.groovy'
    println("** ${iam}: ENTER")

    // This logic seems odd given we branch & tag repositories
    // for release so hilight non-frozen until we know for sure.
    def released=[
	// https://github.com/opencord/voltctl/releases/tag/v1.8.1
	// 'voltha-2.11' : '1.8.1',
	// https://github.com/opencord/voltctl/releases/tag/v1.7.6
	'voltha-2.10' : '1.7.6',
	'voltha-2.9'  : '1.7.4',
	'voltha-2.8'  : '1.6.11',
    ]

    boolean have_released = released.containsKey(branch)
    boolean is_release  = false
    // TODO: Enable with parameter: RELEASE_VOLTHA=
    boolean has_binding = binding.hasVariable('WORKSPACE')

    // WIP: Probe to find out what is available
    print("""
** have_released: ${have_released}
**   has_binding: ${has_binding}
""")

    // ---------------------------------------------
    // Sanity check: released version must be frozen
    // ---------------------------------------------
    if (is_release && ! released.containsKey(branch)) {
	// Fingers crossed: jenkins may rewrite the callstack.
	def myname = this.class.getName()

	String url = [
	    'https://docs.voltha.org/master',
	    'howto/release/installVoltctl.html',
	].join('/')

	String error = [
	    myname, "ERROR:",
	    "Detected release version=[$branch]",
	    "but voltctl is not frozen.",
	    '',
	    "See Also:", url,
	].join(' ')
	throw new Exception(error)
    }

    // --------------------------------
    // General answer: latest available
    // --------------------------------
    if (! have_released) {
	url = 'https://api.github.com/repos/opencord/voltctl/releases/latest'
	get_version = [
	    "curl -sSL ${url}",
	    "jq -r .tag_name",
	    "sed -e 's/^v//g'",
	].join(' | ')
	
	print(" ** RUNNING: ${get_version}")
	released[branch] = sh (
	    script: get_version,
	    returnStdout: true
	).trim()
    }

    voltctlVersion = released[branch]
    println "Installing voltctl version ${voltctlVersion} on branch ${branch}"

    // -----------------------------------------------------------------------
    // groovy expansion: ${var}
    // shell  expansion: \${var}
    // -----------------------------------------------------------------------
    sh returnStdout: false, script: """#!/bin/bash

    set -eu -o pipefail

    bin_voltctl="$WORKSPACE/bin/voltctl"

    mkdir -p "$WORKSPACE/bin"
    cd "$WORKSPACE" || { echo "ERROR: cd $WORKSPACE failed"; exit 1; }

    HOSTOS="\$(uname -s   | tr '[:upper:]' '[:lower:]')"
    HOSTARCH="\$(uname -m | tr '[:upper:]' '[:lower:]')"
    if [ "\$HOSTARCH" == "x86_64" ]; then
       HOSTARCH="amd64"
    fi

    # Retrieve versioned voltctl binary
    download_url="https://github.com/opencord/voltctl/releases/download"
    vol_ver="v${voltctlVersion}"
    vol_name="voltctl-${voltctlVersion}-\${HOSTOS}-\${HOSTARCH}"
    curl -o "\$bin_voltctl" -sSL "\${download_url}/\${vol_ver}/\${vol_name}"

    chmod u=rwx,go=rx "\$bin_voltctl"

    # ---------------------------------------------------------
    # /usr/local/bin/voltctl --version != bin/voltctl --version
    # Problem when default searchpath has not been modified.
    # ---------------------------------------------------------
    "\${bin_voltctl}" version --clientonly
    voltctl version --clientonly
  """

    println("** ${iam}: LEAVE")
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(String branch)
{
    String iam = getIam('main')
    println("** ${iam}: ENTER")

	/* - unused, string passed as argument
    if (!config) {
        config = [:]
    }
	 */

    try
    {
	def config = [:]
	showCommands(config)
    }
    catch (Exception err)
    {
	println("** ${iam}: WARNING ${err}")
    }

    try
    {
	process(branch)
    }
    catch (Exception err)
    {
	println("** ${iam}: EXCEPTION ${err}")
	throw err
    }
    finally
    {
	println("** ${iam}: LEAVE")
    }
    return
}

// [EOF]
