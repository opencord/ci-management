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
    
    apt-get update
    apt-get install -y \
        bzip2 \
        curl \
        git \
        less \
        # oracle-java8-installer \
        # oracle-java8-set-default \
        python \
        ssh \
        zip \
        # maven \
        nodejs \
        # nodejs-legacy \
        npm \
        python-pip \
        # docker-ce \
        # end of apt-get install list
    # npm install -g bower
    # npm install karma --save-dev
    npm install -g typings

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
