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

     DISTRO=$(lsb release -cs)

     apt-add-repository \
       "deb http://ppa.launchpad.net/webupd8team/java/ubuntu $DISTRO main"

     apt-add-respsitory \
       "deb-src http://ppa.launchpad.net/webupd8team/java/ubuntu $DISTRO main"
}

ubuntu_systems() {
    apt-get clean

    ubuntu_install_java_setup

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
        ansible \
        build-essential \
        bzip2 \
        curl \
        docker-ce \
        git \
        less \
        nodejs \
        npm \
        oracle-java8-installer \
        python \
        python-dev \
        python-netaddr \
        python-pip \
        software-properties-common \
        ssh \
        sshpass \
        zip
        # end of apt-get install list

    # install python modules
    pip install \
        ansible-lint \
        astroid \
        docker-compose==1.15.0 \
        docker==2.6.1 \
        gitpython \
        graphviz \
        isort \
        "Jinja2>=2.9" \
        pylint \
        robotframework \
        robotframework-httplibrary \
        robotframework-requests \
        robotframework-sshlibrary
        # end of pip install list

    # install npm modules
    npm install -g \
        gitbook-cli \
        markdownlint \
        typings
        # end of npm install list

    # install repo
    REPO_SHA256SUM="394d93ac7261d59db58afa49bb5f88386fea8518792491ee3db8baab49c3ecda"
    curl -o /tmp/repo 'https://gerrit.opencord.org/gitweb?p=repo.git;a=blob_plain;f=repo;hb=refs/heads/stable'
    echo "$REPO_SHA256SUM  /tmp/repo" | sha256sum -c -
    mv /tmp/repo /usr/local/bin/repo
    chmod a+x /usr/local/bin/repo

    # TODO - install sonarqube scanner
    # https://docs.sonarqube.org/display/SCAN/Analyzing+with+SonarQube+Scanner

    #TODO clean up
    #apt-get clean
    #apt-get purge -y
    #apt-get autoremove -y
    #rm -rf /var/lib/apt/lists/*
    #rm -rf /var/cache/oracle-jdk8-installer
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
