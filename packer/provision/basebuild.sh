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

    # set up ansible repo
    apt-add-repository -y ppa:ansible/ansible

    # set up docker repo
    apt-key adv --keyserver keyserver.ubuntu.com --recv 0EBFCD88
    add-apt-repository \
        "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
         $(lsb_release -cs) \
         stable"

    apt-get update

    # install basic sofware requirements
    apt-get install -y \
        "docker-ce=17.06*" \
        ansible \
        build-essential \
        bzip2 \
        curl \
        git \
        less \
        libxml2-utils \
        nodejs \
        npm \
        python \
        python-dev \
        python-netaddr \
        python-pip \
        ruby \
        ssh \
        sshpass \
        zip
        # end of apt-get install list

    # install python modules
    pip install \
        Jinja2 \
        ansible-lint \
        astroid \
        docker-compose==1.20.1 \
        docker==3.2.1 \
        gitpython \
        graphviz \
        isort \
        linkchecker \
        pexpect \
        pylint \
        pyyaml \
        robotframework \
        robotframework-httplibrary \
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
    SONAR_SCANNER_CLI_VERSION="3.1.0.1141"
    SONAR_SCANNER_CLI_SHA256SUM="efbe7d1a274bbed220846eccc5b36db853a6bab3ee576aebf93ddc604a89ced4"
    curl -L -o /tmp/sonarscanner.zip https://sonarsource.bintray.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${SONAR_SCANNER_CLI_VERSION}-linux.zip
    echo "$SONAR_SCANNER_CLI_SHA256SUM  /tmp/sonarscanner.zip" | sha256sum -c -
    pushd /opt
    unzip /tmp/sonarscanner.zip
    mv sonar-scanner-${SONAR_SCANNER_CLI_VERSION}-linux sonar-scanner
    popd

    # install helm (for lint)
    HELM_VERSION="2.8.2"
    HELM_SHA256SUM="614b5ac79de4336b37c9b26d528c6f2b94ee6ccacb94b0f4b8d9583a8dd122d3"
    HELM_PLATFORM="linux-amd64"
    curl -L -o /tmp/helm.tgz https://storage.googleapis.com/kubernetes-helm/helm-v${HELM_VERSION}-${HELM_PLATFORM}.tar.gz
    echo "$HELM_SHA256SUM  /tmp/helm.tgz" | sha256sum -c -
    pushd /tmp
    tar -xzvf helm.tgz
    mv ${HELM_PLATFORM}/helm /usr/local/bin/helm
    chmod a+x /usr/local/bin/helm
    popd

    # clean up
    apt-get clean
    apt-get purge -y
    apt-get autoremove -y
    rm -rf /var/lib/apt/lists/*
    rm -rf /var/cache/oracle-jdk8-installer
    rm -rf /tmp/sonarscanner.zip
    rm -rf /tmp/helm.tgz
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
