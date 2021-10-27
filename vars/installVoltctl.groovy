// This keyword will install the voltctl based on the branch (e.g.: voltha-2.8 or master)
def call(String branch) {
  sh returnStdout: false, script: """
    mkdir -p $WORKSPACE/bin
    cd $WORKSPACE
    if [ "${branch}" == "voltha-2.8" ]; then
       VOLTCTL_VERSION=1.6.11
    else
       VOLTCTL_VERSION=\$(curl -sSL https://api.github.com/repos/opencord/voltctl/releases/latest | jq -r .tag_name | sed -e 's/^v//g')
    fi
    HOSTOS=\$(uname -s | tr "[:upper:]" "[:lower:"])
    HOSTARCH=\$(uname -m | tr "[:upper:]" "[:lower:"])
    if [ \$HOSTARCH == "x86_64" ]; then
       HOSTARCH="amd64"
    fi
    curl -o $WORKSPACE/bin/voltctl -sSL https://github.com/opencord/voltctl/releases/download/v\${VOLTCTL_VERSION}/voltctl-\${VOLTCTL_VERSION}-\${HOSTOS}-\${HOSTARCH}
    chmod 755 $WORKSPACE/bin/voltctl
    voltctl version --clientonly
  """
}
