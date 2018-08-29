#!/bin/bash
# Ubuntu base build

# vim: ts=4 sw=4 sts=4 et tw=72 :

# force any errors to cause the script and job to end in failure
set -xeu -o pipefail

rh_systems() {
    echo 'No changes to apply'
}

ubuntu_install_java_setup() {

     echo "debconf shared/accepted-oracle-license-v1-1 select true" | debconf-set-selections

     apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys EEA14886

     DISTRO=$(lsb_release -cs)

     apt-add-repository \
       "deb http://ppa.launchpad.net/webupd8team/java/ubuntu $DISTRO main"
}

ubuntu_systems() {
    apt-get clean

    # get prereqs for PPA and apt-over-HTTPS support
    apt-get update
    apt-get install -y apt-transport-https software-properties-common

    # install java (not needed as SonarQube includes this)
    # ubuntu_install_java_setup
    # apt-get update
    # apt-get install -y oracle-java8-installer
    # rm -rf /var/cache/oracle-jdk8-installer

    # set up ansible repo
    apt-add-repository -y ppa:ansible/ansible

    # set up docker repo
    apt-key adv --keyserver keyserver.ubuntu.com --recv 0EBFCD88
    add-apt-repository \
        "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
         $(lsb_release -cs) \
         stable"

    # set up golang repo
    sudo add-apt-repository ppa:gophers/archive

    apt-get update

    # install basic sofware requirements
    apt-get install -y \
        "docker-ce=17.06*" \
        ansible \
        build-essential \
        bzip2 \
        curl \
        git \
        golang-1.10-go \
        less \
        libpcap-dev \
        libxml2-utils \
        maven \
        nodejs \
        npm \
        python \
        python-certifi \
        python-cryptography \
        python-dev \
        python-idna \
        python-netaddr \
        python-openssl \
        python-pip \
        python-urllib3 \
        ruby \
        socat \
        ssh \
        sshpass \
        zip
        # end of apt-get install list

    # install python modules
    pip install \
        Jinja2 \
        ansible-lint \
        astroid==1.* \
        docker-compose==1.20.1 \
        docker==3.2.1 \
        gitpython \
        graphviz \
        grpcio-tools \
        isort \
        linkchecker \
        pexpect \
        pylint==1.* \
        pyyaml \
        robotframework \
        robotframework-httplibrary \
        robotframework-kafkalibrary \
        robotframework-requests \
        robotframework-sshlibrary \
        virtualenv
        # end of pip install list

    # install ruby gems
    gem install \
        mdl
        # end of gem install list

    # install npm modules
    npm install -g \
        gitbook-cli \
        markdownlint \
        typings
        # end of npm install list

    # install golang packages in /usr/local/go
    # Set PATH=$PATH:/usr/local/go/bin` to use these
    export GOPATH=/usr/local/go
    mkdir -p $GOPATH
    export PATH=$PATH:/usr/lib/go-1.10/bin:$GOPATH/bin
    # converters for unit/coverage test
    go get -v github.com/t-yuki/gocover-cobertura
    go get -v github.com/jstemmer/go-junit-report

    # ubuntu 16.04 installs the node binary as /usr/bin/nodejs, which breaks
    # tools that expect it to be named just `node`. Symlink it to fix
    ln -s /usr/bin/nodejs /usr/local/bin/node

    # install repo
    REPO_SHA256SUM="394d93ac7261d59db58afa49bb5f88386fea8518792491ee3db8baab49c3ecda"
    curl -o /tmp/repo 'https://gerrit.opencord.org/gitweb?p=repo.git;a=blob_plain;f=repo;hb=refs/heads/stable'
    echo "$REPO_SHA256SUM  /tmp/repo" | sha256sum -c -
    mv /tmp/repo /usr/local/bin/repo
    chmod a+x /usr/local/bin/repo

    # install sonarqube scanner
    # dl link: https://docs.sonarqube.org/display/SCAN/Analyzing+with+SonarQube+Scanner
    SONAR_SCANNER_CLI_VERSION="3.2.0.1227"
    SONAR_SCANNER_CLI_SHA256SUM="07a50ec270a36cb83f26fe93233819c53c145248c638f4591880f1bd36e331d6"
    curl -L -o /tmp/sonarscanner.zip "https://sonarsource.bintray.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${SONAR_SCANNER_CLI_VERSION}-linux.zip"
    echo "$SONAR_SCANNER_CLI_SHA256SUM  /tmp/sonarscanner.zip" | sha256sum -c -
    pushd /opt
    unzip /tmp/sonarscanner.zip
    mv sonar-scanner-${SONAR_SCANNER_CLI_VERSION}-linux sonar-scanner
    rm -f /tmp/sonarscanner.zip
    popd

    # install helm
    HELM_VERSION="2.9.1"
    HELM_SHA256SUM="56ae2d5d08c68d6e7400d462d6ed10c929effac929fedce18d2636a9b4e166ba"
    HELM_PLATFORM="linux-amd64"
    curl -L -o /tmp/helm.tgz "https://storage.googleapis.com/kubernetes-helm/helm-v${HELM_VERSION}-${HELM_PLATFORM}.tar.gz"
    echo "$HELM_SHA256SUM  /tmp/helm.tgz" | sha256sum -c -
    pushd /tmp
    tar -xzvf helm.tgz
    mv ${HELM_PLATFORM}/helm /usr/local/bin/helm
    chmod a+x /usr/local/bin/helm
    rm -rf helm.tgz ${HELM_PLATFORM}
    popd

    # install kubectl
    KUBECTL_VERSION="1.10.5"
    KUBECTL_SHA256SUM="a9e7f82e516aa8a652bc485620483ea964eb940787cabf890d7eab96eaac294d"
    curl -L -o /tmp/kubectl "https://storage.googleapis.com/kubernetes-release/release/v${KUBECTL_VERSION}/bin/linux/amd64/kubectl"
    echo "$KUBECTL_SHA256SUM  /tmp/kubectl" | sha256sum -c -
    mv /tmp/kubectl /usr/local/bin/kubectl
    chmod a+x /usr/local/bin/kubectl
    rm -f /tmp/kubectl

    # install minikube
    MINIKUBE_VERSION="0.28.0"
    MINIKUBE_DEB_VERSION="$(echo ${MINIKUBE_VERSION} | sed -n 's/\(.*\)\.\(.*\)/\1-\2/p')"
    MINIKUBE_SHA256SUM="5308f955f802a81cdb624fa8f6f5aeead642d203f25efd706cc55f04481bf7a3"
    curl -L -o /tmp/minikube.deb "https://storage.googleapis.com/minikube/releases/v${MINIKUBE_VERSION}/minikube_${MINIKUBE_DEB_VERSION}.deb"
    echo "$MINIKUBE_SHA256SUM  /tmp/minikube.deb" | sha256sum -c -
    pushd /tmp
    dpkg -i minikube.deb
    rm -f minikube.deb
    popd

    # install protobufs
    PROTOC_VERSION="3.3.0"
    PROTOC_SHA256SUM="feb112bbc11ea4e2f7ef89a359b5e1c04428ba6cfa5ee628c410eccbfe0b64c3"
    curl -L -o /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip https://github.com/google/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip
    echo "$PROTOC_SHA256SUM  /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip" | sha256sum -c -
    unzip /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip -d /tmp/protoc3
    mv /tmp/protoc3/bin/* /usr/local/bin/
    mv /tmp/protoc3/include/* /usr/local/include/
    # give sudo permissions on minikube and protoc to jenkins user
    cat <<EOF >/etc/sudoers.d/88-jenkins-minikube-protoc
Cmnd_Alias CMDS = /usr/local/bin/protoc, /usr/bin/minikube
Defaults:jenkins !requiretty
jenkins ALL=(ALL) NOPASSWD:SETENV: CMDS
EOF

    # clean up
    apt-get clean
    apt-get purge -y
    apt-get autoremove -y
    rm -rf /var/lib/apt/lists/*
}

all_systems() {
    echo 'No common distribution configuration to perform'
}

echo "---> Detecting OS"
ORIGIN=$(facter operatingsystem | tr '[:upper:]' '[:lower:]')

case "${ORIGIN}" in
    fedora|centos|redhat)
        echo "---> RH type system detected"
        rh_systems
    ;;
    ubuntu)
        echo "---> Ubuntu system detected"
        ubuntu_systems
    ;;
    *)
        echo "---> Unknown operating system"
    ;;
esac

# execute steps for all systems
all_systems
