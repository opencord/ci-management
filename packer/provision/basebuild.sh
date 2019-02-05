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
    add-apt-repository ppa:gophers/archive

    # set up kubernetes repo
    cat << EOF | base64 -d > /tmp/k8s-apt-key.gpg
mQENBFUd6rIBCAD6mhKRHDn3UrCeLDp7U5IE7AhhrOCPpqGF7mfTemZYHf/5JdjxcOxoSFlK
7zwmFr3lVqJ+tJ9L1wd1K6P7RrtaNwCiZyeNPf/Y86AJ5NJwBe0VD0xHTXzPNTqRSByVYtdN
94NoltXUYFAAPZYQls0x0nUD1hLMlOlC2HdTPrD1PMCnYq/NuL/Vk8sWrcUt4DIS+0RDQ8tK
Ke5PSV0+PnmaJvdF5CKawhh0qGTklS2MXTyKFoqjXgYDfY2EodI9ogT/LGr9Lm/+u4OFPvmN
9VN6UG+s0DgJjWvpbmuHL/ZIRwMEn/tpuneaLTO7h1dCrXC849PiJ8wSkGzBnuJQUbXnABEB
AAG0QEdvb2dsZSBDbG91ZCBQYWNrYWdlcyBBdXRvbWF0aWMgU2lnbmluZyBLZXkgPGdjLXRl
YW1AZ29vZ2xlLmNvbT6JAT4EEwECACgFAlUd6rICGy8FCQWjmoAGCwkIBwMCBhUIAgkKCwQW
AgMBAh4BAheAAAoJEDdGwginMXsPcLcIAKi2yNhJMbu4zWQ2tM/rJFovazcY28MF2rDWGOnc
9giHXOH0/BoMBcd8rw0lgjmOosBdM2JT0HWZIxC/Gdt7NSRA0WOlJe04u82/o3OHWDgTdm9M
S42noSP0mvNzNALBbQnlZHU0kvt3sV1YsnrxljoIuvxKWLLwren/GVshFLPwONjw3f9Fan6G
WxJyn/dkX3OSUGaduzcygw51vksBQiUZLCD2Tlxyr9NvkZYTqiaWW78L6regvATsLc9L/dQU
iSMQZIK6NglmHE+cuSaoK0H4ruNKeTiQUw/EGFaLecay6Qy/s3Hk7K0QLd+gl0hZ1w1VzIeX
Lo2BRlqnjOYFX4CwAgADmQENBFrBaNsBCADrF18KCbsZlo4NjAvVecTBCnp6WcBQJ5oSh7+E
98jX9YznUCrNrgmeCcCMUvTDRDxfTaDJybaHugfba43nqhkbNpJ47YXsIa+YL6eEE9emSmQt
jrSWIiY+2YJYwsDgsgckF3duqkb02OdBQlh6IbHPoXB6H//b1PgZYsomB+841XW1LSJPYlYb
IrWfwDfQvtkFQI90r6NknVTQlpqQh5GLNWNYqRNrGQPmsB+NrUYrkl1nUt1LRGu+rCe4bSaS
mNbwKMQKkROE4kTiB72DPk7zH4Lm0uo0YFFWG4qsMIuqEihJ/9KNX8GYBr+tWgyLooLlsdK3
l+4dVqd8cjkJM1ExABEBAAG0QEdvb2dsZSBDbG91ZCBQYWNrYWdlcyBBdXRvbWF0aWMgU2ln
bmluZyBLZXkgPGdjLXRlYW1AZ29vZ2xlLmNvbT6JAT4EEwECACgFAlrBaNsCGy8FCQWjmoAG
CwkIBwMCBhUIAgkKCwQWAgMBAh4BAheAAAoJEGoDCyG6B/T78e8H/1WH2LN/nVNhm5TS1VYJ
G8B+IW8zS4BqyozxC9iJAJqZIVHXl8g8a/Hus8RfXR7cnYHcg8sjSaJfQhqO9RbKnffiuQgG
rqwQxuC2jBa6M/QKzejTeP0Mgi67pyrLJNWrFI71RhritQZmzTZ2PoWxfv6b+Tv5v0rPaG+u
t1J47pn+kYgtUaKdsJz1umi6HzK6AacDf0C0CksJdKG7MOWsZcB4xeOxJYuy6NuO6KcdEz8/
XyEUjIuIOlhYTd0hH8E/SEBbXXft7/VBQC5wNq40izPi+6WFK/e1O42DIpzQ749ogYQ1eode
xPNhLzekKR3XhGrNXJ95r5KO10VrsLFNd8KwAgAD
EOF

    sudo apt-key add /tmp/k8s-apt-key.gpg
    echo "deb http://apt.kubernetes.io/ kubernetes-xenial main" | sudo tee /etc/apt/sources.list.d/kubernetes.list

    # update after adding apt repos to sources
    apt-get update

    # install basic sofware requirements
    apt-get install -y \
        "docker-ce=17.06*" \
        apt-transport-https \
        ansible \
        build-essential \
        bzip2 \
        curl \
        ebtables \
        ethtool \
        git \
        golang-1.10-go \
        httpie \
        jq \
        kafkacat \
        "kubeadm=1.12.4-*" \
        "kubelet=1.12.4-*" \
        "kubectl=1.12.4-*" \
        less \
        libmysqlclient-dev \
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
        coverage \
        docker-compose==1.20.1 \
        docker==3.2.1 \
        gitpython \
        graphviz \
        grpcio-tools \
        isort \
        linkchecker \
        mock \
        nose2 \
        pexpect \
        pylint==1.* \
        pyyaml \
        robotframework \
        robotframework-httplibrary \
        robotframework-kafkalibrary \
        robotframework-requests \
        robotframework-sshlibrary \
        tox \
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

    # dep for go package dependencies w/versioning, version v0.5.0, adapted from:
    #  https://golang.github.io/dep/docs/installation.html#install-from-source
    go get -d -u github.com/golang/dep
    pushd $(go env GOPATH)/src/github.com/golang/dep
      git checkout "v0.5.0"
      go install -ldflags="-X main.version=v0.5.0" ./cmd/dep
    popd

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
    curl -L -o /tmp/sonarscanner.zip "https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${SONAR_SCANNER_CLI_VERSION}-linux.zip"
    echo "$SONAR_SCANNER_CLI_SHA256SUM  /tmp/sonarscanner.zip" | sha256sum -c -
    pushd /opt
    unzip /tmp/sonarscanner.zip
    mv sonar-scanner-${SONAR_SCANNER_CLI_VERSION}-linux sonar-scanner
    rm -f /tmp/sonarscanner.zip
    popd

    # install helm
    HELM_VERSION="2.11.0"
    HELM_SHA256SUM="02a4751586d6a80f6848b58e7f6bd6c973ffffadc52b4c06652db7def02773a1"
    HELM_PLATFORM="linux-amd64"
    curl -L -o /tmp/helm.tgz "https://storage.googleapis.com/kubernetes-helm/helm-v${HELM_VERSION}-${HELM_PLATFORM}.tar.gz"
    echo "$HELM_SHA256SUM  /tmp/helm.tgz" | sha256sum -c -
    pushd /tmp
    tar -xzvf helm.tgz
    mv ${HELM_PLATFORM}/helm /usr/local/bin/helm
    chmod a+x /usr/local/bin/helm
    rm -rf helm.tgz ${HELM_PLATFORM}
    popd

    # install minikube
    MINIKUBE_VERSION="0.30.0"
    MINIKUBE_DEB_VERSION="$(echo ${MINIKUBE_VERSION} | sed -n 's/\(.*\)\.\(.*\)/\1-\2/p')"
    MINIKUBE_SHA256SUM="c6c5aa5956f8ad5f61d426e9b8601ba95965a9c30bb80a9fe7525c64e6dd12fd"
    curl -L -o /tmp/minikube.deb "https://storage.googleapis.com/minikube/releases/v${MINIKUBE_VERSION}/minikube_${MINIKUBE_DEB_VERSION}.deb"
    echo "$MINIKUBE_SHA256SUM  /tmp/minikube.deb" | sha256sum -c -
    pushd /tmp
    dpkg -i minikube.deb
    rm -f minikube.deb
    popd

    # install protobufs
    PROTOC_VERSION="3.6.1"
    PROTOC_SHA256SUM="6003de742ea3fcf703cfec1cd4a3380fd143081a2eb0e559065563496af27807"
    curl -L -o /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip https://github.com/google/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip
    echo "$PROTOC_SHA256SUM  /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip" | sha256sum -c -
    unzip /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip -d /tmp/protoc3
    mv /tmp/protoc3/bin/* /usr/local/bin/
    mv /tmp/protoc3/include/* /usr/local/include/
    # fix permissions on include files
    chmod -R a+r /usr/local/include/

    # give sudo permissions on minikube and protoc to jenkins user
    cat <<EOF >/etc/sudoers.d/88-jenkins-minikube-protoc
Cmnd_Alias CMDS = /usr/local/bin/protoc, /usr/bin/minikube
Defaults:jenkins !requiretty
jenkins ALL=(ALL) NOPASSWD:SETENV: CMDS
EOF

    # remove apparmor
    service apparmor stop
    update-rc.d -f apparmor remove
    apt-get remove apparmor-utils libapparmor-perl apparmor
    update-grub

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
