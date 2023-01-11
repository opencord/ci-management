#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Install the kail command 
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getIam(String func)
{
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/installKail.groovy'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def process {

    String iam = 'vars/installVoltctl.groovy'
    println("** ${iam}: ENTER")

    // --------------------------------
    // Read latest version
    // --------------------------------
    url = 'https://api.github.com/repos/boz/kail/releases/latest'
    get_version = [
        "curl -sSL ${url}",
        "jq -r .tag_name",
        "sed -e 's/^v//g'",
    ].join(' | ')

    print(" ** RUNNING: ${get_version}")
    kailVersion = sh (
       script: get_version,
       returnStdout: true
    ).trim()

    println "Installing kail version ${kailVersion}"

    // -----------------------------------------------------------------------
    // groovy expansion: ${var}
    // shell  expansion: \${var}
    // -----------------------------------------------------------------------
    sh returnStdout: false, script: """#!/bin/bash

      set -eu -o pipefail

      bin_dir="$WORKSPACE/bin"

      mkdir -p "$WORKSPACE/bin"
      cd "$WORKSPACE" || { echo "ERROR: cd $WORKSPACE failed"; exit 1; }

      HOSTOS="\$(uname -s   | tr '[:upper:]' '[:lower:]')"
      HOSTARCH="\$(uname -m | tr '[:upper:]' '[:lower:]')"
      if [ "\$HOSTARCH" == "x86_64" ]; then
        HOSTARCH="amd64"
      fi

      tmpdir=$(mktemp -d)

      # Retrieve versioned kail archive
      download_url="https://github.com/boz/kail/releases/download"
      kail_ver="v${kailVersion}"
      kail_name="kail_${kailVersion}_\${HOSTOS}_\${HOSTARCH}.tar.gz"
      curl -o "${tmpdir}/${kail_name}" -sSL "\${download_url}/\${kail_ver}/\${kail_name}"

      (cd "\${tmpdir}" && tar -xzf "\${kail_name}")
      install "\${tmpdir}/kail" "\${bin_dir}/"

      rm -rf "${tmpdir}"

    """

    println("** ${iam}: LEAVE")
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call
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
    process
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
