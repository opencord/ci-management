#!/bin/bash
# Ubuntu base build

# vim: ts=4 sw=4 sts=4 et tw=72 :

# force any errors to cause the script and job to end in failure
set -xeu -o pipefail

rh_systems() {
    echo 'No changes to apply'
}

# ubuntu_install_java_setup() {
#     DISTRO="xenial" # TODO get this programatically
#     echo debconf shared/accepted-oracle-license-v1-1 select true | debconf-set-selections
#     echo "deb http://ppa.launchpad.net/webupd8team/java/ubuntu $DISTRO main" | \
#         tee /etc/apt/sources.list.d/webupd8team-java.list
#     echo "deb-src http://ppa.launchpad.net/webupd8team/java/ubuntu $DISTRO main" | \
#         tee -a /etc/apt/sources.list.d/webupd8team-java.list
#     apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys EEA14886
# }

ubuntu_systems() {
    apt-get clean
    # ubuntu_install_java_setup

    # set up docker repo
    # curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
    # sudo add-apt-repository \
    #     "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
    #      $(lsb_release -cs) \
    #      stable"

    # set up ansible repo
    sudo apt-add-repository -y ppa:ansible/ansible

    # set up docker repo
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
    sudo add-apt-repository \
        "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
         $(lsb_release -cs) \
         stable"

    apt-get update

    # install basic sofware requirements
    apt-get install -y \
        ansible \
        apt-transport-https \
        build-essential \
        bzip2 \
        curl \
        git \
        less \
        python \
        ssh \
        zip \
        nodejs \
        npm \
        python-dev \
        python-netaddr \
        python-pip \
        sshpass \
        software-properties-common \
        docker-ce
        # end of apt-get install list

    # install python modules
    sudo pip install \
        docker==2.6.1 \
        docker-compose==1.15.0 \
        gitpython \
        graphviz \
        "Jinja2>=2.9" \
        robotframework \
        robotframework-sshlibrary \
        robotframework-requests \
        robotframework-httplibrary \
	pexpect

    # install npm modules
    npm install -g \
        typings

    # install repo
    curl -o /tmp/repo 'https://gerrit.opencord.org/gitweb?p=repo.git;a=blob_plain;f=repo;hb=refs/heads/stable'
    sudo mv /tmp/repo /usr/local/bin/repo
    sudo chmod a+x /usr/local/bin/repo

    #TODO clean up
    #apt-get clean
    #apt-get purge -y
    #apt-get autoremove -y
    #rm -rf /var/lib/apt/lists/*
    #rm -rf /var/cache/oracle-jdk8-installer
    echo 'No changes to apply'
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
