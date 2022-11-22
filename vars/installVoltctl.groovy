// This keyword will install the voltctl based on the branch (e.g.: voltha-2.8 or master)
def call(String branch)
{
    String iam = 'ci-management:installVoltctl'
    print("** ${iam}: ENTER")

    // [TODO] Move values into a central config directory and load
    // as a config file to avoid hardcoding values in source.
    Map released=\
    [
        'voltha-2.8'  : '1.6.11',
        'voltha-2.9'  : '1.7.4',
        'voltha-2.10' : '1.7.6',
        // 'voltha-2.11' : '1.7.6',
    ]

    if (! released.containsKey(branch))
    {
        latest = sh(
            returnStdout: true,
            script: """
#!/bin/bash
# set -euo pipefail

curl -sSL https://api.github.com/repos/opencord/voltctl/releases/latest \
    | jq -r .tag_name \
    | sed -e 's/^v//g'
"""
        ).trim()

	release[branch] = latest
    }
    voltctlVersion = released[branch]

    println "Installing voltctl version ${voltctlVersion} on branch ${branch}"

    sh(
        returnStdout: false,
        script: """
    #!/bin/bash

    # set -euo pipefail

    mkdir -p "$WORKSPACE/bin"
    cd "$WORKSPACE" || { echo "ERROR: cd [$WORKSPACE] failed"; echo 1 }
    HOSTOS=\$(uname -s | tr "[:upper:]" "[:lower:"])
    HOSTARCH=\$(uname -m | tr "[:upper:]" "[:lower:"])
    if [ \$HOSTARCH == "x86_64" ]; then
       HOSTARCH="amd64"
    fi

    voltctl="$WORKSPACE/bin/voltctl"
    curl -o "$voltctl" -sSL "https://github.com/opencord/voltctl/releases/download/v${voltctlVersion}/voltctl-${voltctlVersion}-\${HOSTOS}-\${HOSTARCH}"
    chmod 755 "$voltctl"
    voltctl version --clientonly
  """)

    print("** ${iam}: LEAVE")
}
