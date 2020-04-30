#!/bin/bash
# Ubuntu base build

# vim: ts=4 sw=4 sts=4 et tw=72 :

# force any errors to cause the script and job to end in failure
set -xeu -o pipefail

rh_systems() {
    echo 'No changes to apply'
}

ubuntu_systems() {
    DISTRO=$(lsb_release -cs)

    apt-get clean

    # get prereqs for PPA and apt-over-HTTPS support
    apt-get update
    apt-get install -y apt-transport-https software-properties-common

    # set up git backports repo
    add-apt-repository -y  ppa:git-core/ppa

    # set up docker repo
    apt-key adv --keyserver keyserver.ubuntu.com --recv 0EBFCD88
    add-apt-repository \
        "deb [arch=amd64] https://download.docker.com/linux/ubuntu \
         $(lsb_release -cs) \
         stable"

    # set up golang repo
    # docs: https://github.com/golang/go/wiki/Ubuntu
    add-apt-repository ppa:longsleep/golang-backports

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

    # set up NodeJS repo
    # Instructions: https://github.com/nodesource/distributions/blob/master/README.md#manual-installation

    cat << EOF | base64 -d > /tmp/nodejs-apt-key.gpg
LS0tLS1CRUdJTiBQR1AgUFVCTElDIEtFWSBCTE9DSy0tLS0tClZlcnNpb246IEdudVBHIHYxCkNvbW1lbnQ6IEdQR1Rvb2xzIC0gaHR0cHM6Ly9ncGd0b29scy5vcmcKCm1RSU5CRk9iSkxZQkVBRGtGVzhITWpzb1lSSlE0bkNZQy82RWgweUxXSFdmQ2grLzlaU0lqNHcvcE9lMlY2VisKVzZESFkza0szYSsyYnhyYXg5RXFLZTd1eGtTS2Y5NWdmbnMrSTkrUitSSmZScGIxcXZsalVScjU0eTM1SVpncwpmTUcyMk5wK1RtTTJSTGdkRkNaYTE4aDArUmJIOWkwYitackI5WFBabUxiL2g5b3U3U293R3FRM3d3T3RUM1Z5CnFtaWYwQTJHQ2NqRlRxV1c2VFhhWThlWko5QkNFcVczay8wQ2p3N0svbVN5L3V0eFlpVUl2Wk5LZ2FHL1A4VTcKODlReXZ4ZVJ4QWY5M1lGQVZ6TVhob0t4dTEySXVINFZuU3dBZmI4Z1F5eEtSeWlHT1V3azBZb0JQcHFSbk1tRApEbDdTZG1ZM29RSEVKekJlbFRNalRNOEFqYkI5bVdvUEJYNUc4dDR1NDcvRlo2UGdkZm1SZzloc0tYaGtMSmM3CkMxYnRibE9ITmdEeDE5ZnpBU1dYK3hPalppS3BQNk1rRUV6cTFiaWxVRnVsNlJEdHhrVFdzVGE1VEdpeGdDQi8KRzJmSzhJOUpML3lRaERjNk9HWTltalBPeE1iNVBnVWxUOG94M3Y4d3QyNWVyV2o5ejMwUW9FQndmU2c0dHpMYwpKcTZOL2llcFFlbU5mbzZJcytURytKekk2dmhYamxzQm0vWG16MFppRlBQT2JBSC92R0NZNUk2ODg2dlhRN2Z0CnFXSFlIVDhqei9SNHRpZ01HQyt0dlova2NtWUJzTENDSTV1U0VQNkpKUlFRaEhyQ3ZPWDBVYXl0SXRmc1FmTG0KRVlSZDJGNzJvMXlHaDN5dldXZkRJQlhSbWFCdUlHWEdwYWpDMEp5QkdTT1diOVV4TU5aWS8yTEpFd0FSQVFBQgp0QjlPYjJSbFUyOTFjbU5sSUR4bmNHZEFibTlrWlhOdmRYSmpaUzVqYjIwK2lRSTRCQk1CQWdBaUJRSlRteVMyCkFoc0RCZ3NKQ0FjREFnWVZDQUlKQ2dzRUZnSURBUUllQVFJWGdBQUtDUkFXVmFDcmFGZGlnSFRtRC85T0toVXkKakoraDhnTVJnNnJpNUVReE9FeGNjU1JVMGk3VUhrdGVjU3MwRFZDNGxaRzlBT3pCZStRMzZjeW01WjFkaTZKUQprSGw2OXEzekJkVjNLVFcrSDFwZG1uWmxlYllHejhwYUc5aVEvd1M5Z3BuU2VFeXgwRW55aTE2N0J6bTBPNEExCkdLMHBya0xuei95Uk9ISEVmSGpzVGdNdkZ3QW5mOXVheHdXZ0UxZDFSaXRJV2dKcEFucDFEWjVPMHVWbHNQUG0KWEFodUJKMzJtVThTNUJlelBUdUpKSUN3QmxMWUVDR2IxWTY1Q2lsNE9BTFU3VDdzYlVxZkxDdWFSS3h1UHRjVQpWbko2L3FpeVB5Z3ZLWldoVjZPZDBZeGx5ZWQxa2Z0TUp5WW9MOGtQSGZlSEordkl5dDBzN2Nyb3BmaXdYb2thCjFpSkI1bkt5dC9lcU1uUFE5YVJwcWttOUFCUy9yN0FhdU1BLzlSQUx1ZFFSSEJkV0l6ZklnME1scWI1Mnl5VEkKSWdRSkhOR05YMVQzejFYZ1poSStWaThTTEZGU2g4eDlGZVVaQzZZSnUwVlhYajVpeitlWm1rL25ZalV0NE10YwpwVnNWWUlCN29JREliSW1PRG04Z2dzZ3JJenF4T3pRVlAxenNDR2VrNVU2UUZjOUdZclErV3YzL2ZHOGhma0RuCnhYTHd3ME9HYUVReGZvZG04Y0xGWjViOEphRzMrWXhmZTdKa05jbHd2UmltdmxBanFJaVc1T0swdnZmSGNvK1kKZ0FOaFFybE1uVHgvL0lkWnNzYXh2WXl0U0hwUFpUWXcrcVBFamJCSk9McG9Mcno4WmFmTjF1ZWtwQXFRamZmSQpBT3FXOVNkSXpxL2tTSGdsMGJ6V2JQSlB3ODZYenpmdGV3aktOYmtDRFFSVG15UzJBUkFBeFNTZFFpK1dwUFFaCmZPZmxreDlzWUphMGNXekxsMncrK0ZRbloxUG41RjA5RC9rUE1OaDRxT3N5dlhXbGVrYVYvU3NlRFp0VnppSEoKS202VjhUQkczZmxtRmxDM0RXUWZOTkZ3bjUrcFdTQjhXSEc0YlRBNVJ5WUVFWWZwYmVrTXRkb1dXL1JvOEttaAo0MW51eFpEU3VCSmhEZUZJcDBjY25OMkxwMW82WGZJZURZUGVneUVQU1NacXJ1ZGZxTHJTWmhTdERsSmdYamVhCkpqVzZVUDZ0eFB0WWFhaWxhOS9IbjZ2Rjg3QVE1YlIyZEVXQi94Ukp6Z053UmlheDdLU1UweGNhNnhBdWYrVEQKeENqWjVwcDJKd2RDanF1WExUbVVuYklaOUxHVjU0VVovTWVpRzh5VnU2cHhiaUduWG80RWtiazZ4Z2kxZXdMaQp2R216NFFSZlZrbFYwZGJhM1pqMGZSb3pmWjIycVVIeENmRE03YWQwZUJYTUZtSGlOOGhnM0lVSFRPK1VkbFgvCmFIM2dBREZBdlNWRHYwdjh0NmRHYzZYRTlEcjdtR0VGblFNSE80emhNMUhhUzJOaDBUaUwydEZMdHRMYmZHNW8KUWx4Q2ZYWDkvbmFzajNLOXFubEVnOUczKzRUN2xwZFBtWlJSZTFPOGNIQ0k1aW1WZzZjTElpQkxQTzE2ZTBmSwp5SElnWXN3TGRySkZmYUhOWU0vU1dKeEhwWDc5NXpuK2lDd3l2WlNsTGZIOW1sZWdPZVZtajljeWhOL1ZPbVMzClFSaGxZWG9BMno3V1pUTm9DNmlBSWx5SXBNVGNacitudGFHVnRGT0xTNmZ3ZEJxRFhqbVNRdTY2bURLd1U1RWsKZk5sYnlycHpaTXlGQ0RXRVlvNEFJUi8xOGFHWkJZVUFFUUVBQVlrQ0h3UVlBUUlBQ1FVQ1U1c2t0Z0liREFBSwpDUkFXVmFDcmFGZGlnSVBRRUFDY1loOHJSMTl3TVpaL2hnWXY1c282WTFIY0pOQVJ1em1mZlFLb3pTL3J4cWVjCjB4TTN3Y2VMMUFJTXVHaGxYRmVHZDB3UnYvUlZ6ZVpqblRHd2hOMURuQ0R5MUk2NmhVVGdlaE9Oc2ZWYW51UDEKUFpLb0wzOEVBeHNNemRZZ2tZSDZUOWE0d0pIL0lQdCt1dUZURkZ5M284VEtNdkthSms5OCtKc3AyWC9RdU54aApxcGNJR2FWYnRRMWJuN20razVRZS9meitiRnVVZVhQaXZhZkxMbEdjNktiZGdNdlNXOUVWTU83eUJ5LzJKRTE1ClpKZ2w3bFhLTFEzMVZRUEFIVDNhbjVJVjJDL2llMTJlRXFaV2xuQ2lIVi93VCt6aE9rU3BXZHJoZVdmQlQrYWMKaFI0akRIODBBUzNGOGpvM2J5UUFUSmIzUm9DWVVDVmMzdTFvdWhOWmE1eUxnWVovaVprcGs1Z0tqeEhQdWRGYgpEZFdqYkdmbE45azE3VkNmNFo5eUFiOVFNcUh6SHdJR1hyYjdyeUZjdVJPTUNMTFZVcDA3UHJUclJ4bk85QS80Cnh4RUNpMGwvQnpOeGVVMWdLODhoRWFOaklmdmlQUi9oNkdxNktPY05LWjhyVkZkd0ZwamJ2d0hNUUJXaHJxZnUKRzNLYWVQdmJuT2JLSFhwZklLb0FNN1gycWZPK0lGbkxHVFB5aEZUY3JsNnZaQlRNWlRmWmlDMVhEUUx1R1VuZApzY2t1WElOSVUzREZXelpHcjBRcnFrdUUvanlyN0ZYZVVKajlCN2NMbytzL1RYbytSYVZmaTNrT2M5Qm94SXZ5Ci9xaU5Hcy9US3kyL1VqcXAvYWZmbUlNb01YU296S21nYTgxSlN3a0FETzFKTWdVeTZkQXBYejlrUDRFRTNnPT0KPUNMR0YKLS0tLS1FTkQgUEdQIFBVQkxJQyBLRVkgQkxPQ0stLS0tLQo=
EOF

    sudo apt-key add /tmp/nodejs-apt-key.gpg
    NODE_VERSION=node_7.x
    echo "deb https://deb.nodesource.com/$NODE_VERSION $DISTRO main" | sudo tee /etc/apt/sources.list.d/nodesource.list
    echo "deb-src https://deb.nodesource.com/$NODE_VERSION $DISTRO main" | sudo tee -a /etc/apt/sources.list.d/nodesource.list

    # update after adding apt repos to sources
    apt-get update

    # install basic sofware requirements
    apt-get install -y \
        "docker-ce=17.06*" \
        apt-transport-https \
        build-essential \
        bzip2 \
        curl \
        ebtables \
        enchant \
        ethtool \
        git \
        golang-1.12-go \
        graphviz \
        jq \
        kafkacat \
        "kubeadm=1.12.7-*" \
        "kubelet=1.12.7-*" \
        "kubectl=1.12.7-*" \
        less \
        libmysqlclient-dev \
        libpcap-dev \
        libxml2-utils \
        maven \
        nodejs \
        python \
        python-dev \
        python-pip \
        python3-dev \
        ruby \
        screen \
        socat \
        ssh \
        sshpass \
        zip
        # end of apt-get install list

    # remove apt installed incompatible python tools
    # NOTE: Python3 versions are not removed, as cloud-init depends on them
    apt-get -y remove \
      python-enum34 \
      python-cryptography \
      python-openssl \
      python-ndg-httpsclient \
      python-requests \
      python-six \
      python-urllib3

    # install python modules
    # upgrade pip or other installations may fail in unusual ways
    pip install --upgrade pip
    pip install \
        Jinja2 \
        ansible \
        ansible-lint \
        astroid==1.* \
        coverage \
        certifi \
        cryptography \
        docker-compose==1.20.1 \
        docker==3.2.1 \
        gitpython \
        git-review \
        graphviz \
        grpcio-tools \
        httpie==1.0.3 \
        isort \
        git+https://github.com/linkchecker/linkchecker.git@v9.4.0 \
        more-itertools==5.0.0 \
        mock \
        netaddr \
        ndg-httpsclient \
        nose2 \
        pyopenssl \
        pexpect \
        pylint==1.* \
        pyyaml \
        requests \
        robotframework \
        robotframework-httplibrary \
        robotframework-kafkalibrary \
        robotframework-lint \
        robotframework-requests \
        robotframework-sshlibrary \
        six \
        tox \
        twine==1.15.0 \
        urllib3 \
        virtualenv \
        yamllint
        # end of pip install list

    # install ruby gems
    gem install \
        mdl -v 0.5.0
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
    export PATH=$PATH:/usr/lib/go-1.12/bin:$GOPATH/bin

    # converters for unit/coverage test
    go get -v github.com/t-yuki/gocover-cobertura
    go get -v github.com/jstemmer/go-junit-report

    # gothub - uploader for github artifacts
    go get -v github.com/itchio/gothub

    # dep for go package dependencies w/versioning, version 0.5.2, adapted from:
    #  https://golang.github.io/dep/docs/installation.html#install-from-source
    go get -d -u github.com/golang/dep
    pushd $(go env GOPATH)/src/github.com/golang/dep
      git checkout "0.5.2"
      go install -ldflags="-X main.version=0.5.2" ./cmd/dep
    popd

    # golangci-lint for testing
    #  https://github.com/golangci/golangci-lint#local-installation
    GO111MODULE=on go get github.com/golangci/golangci-lint/cmd/golangci-lint@v1.17.1

    # protoc-gen-go - Golang protbuf compiler extension for protoc (installed
    # below)
    go get -d -u github.com/golang/protobuf/protoc-gen-go
    pushd $(go env GOPATH)/src/github.com/golang/protobuf
      git checkout "v1.3.1"
      go install ./protoc-gen-go
    popd

    # install repo launcher v2.0
    REPO_B64_SHA256SUM="f34b0743ae46105df575f116fc6535a5d9db10c575e03e11e2932e2a8745061e"
    curl -o /tmp/repo.b64 'https://gerrit.googlesource.com/git-repo/+/refs/tags/v2.0/repo?format=TEXT'
    echo "$REPO_B64_SHA256SUM  /tmp/repo.b64" | sha256sum -c -
    base64 --decode /tmp/repo.b64 > /tmp/repo
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
    HELM_VERSION="2.14.2"
    HELM_SHA256SUM="9f50e69cf5cfa7268b28686728ad0227507a169e52bf59c99ada872ddd9679f0"
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
    PROTOC_VERSION="3.7.0"
    PROTOC_SHA256SUM="a1b8ed22d6dc53c5b8680a6f1760a305b33ef471bece482e92728f00ba2a2969"
    curl -L -o /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip https://github.com/google/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip
    echo "$PROTOC_SHA256SUM  /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip" | sha256sum -c -
    unzip /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip -d /tmp/protoc3
    mv /tmp/protoc3/bin/* /usr/local/bin/
    mv /tmp/protoc3/include/* /usr/local/include/
    # fix permissions on files
    chmod -R a+rx /usr/local/bin/*
    chmod -R a+rX /usr/local/include/

    # give sudo permissions on minikube and protoc to jenkins user
    cat <<EOF >/etc/sudoers.d/88-jenkins-minikube-protoc
Cmnd_Alias CMDS = /usr/local/bin/protoc, /usr/bin/minikube
Defaults:jenkins !requiretty
jenkins ALL=(ALL) NOPASSWD:SETENV: CMDS
EOF

    # install hadolint (Dockerfile checker)
    HADOLINT_VERSION="1.17.1"
    HADOLINT_SHA256SUM="2f8f3bf120e9766e6e79f7a86fed8ede55ebbf2042175b68a7c899a74eabbf34"
    curl -L -o /tmp/hadolint https://github.com/hadolint/hadolint/releases/download/v${HADOLINT_VERSION}/hadolint-Linux-x86_64
    echo "$HADOLINT_SHA256SUM  /tmp/hadolint" | sha256sum -c -
    mv /tmp/hadolint /usr/local/bin/hadolint
    chmod -R a+rx /usr/local/bin/hadolint

    # install pandoc (document converter)
    PANDOC_VERSION="2.8.0.1"
    PANDOC_SHA256SUM="81cca90353dced1e285888b73f2bee55ed388d34b6b0624d76a2eba2344eaba9"
    curl -L -o /tmp/pandoc.deb "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/pandoc-${PANDOC_VERSION}-1-amd64.deb"
    echo "$PANDOC_SHA256SUM  /tmp/pandoc.deb" | sha256sum -c -
    dpkg -i /tmp/pandoc.deb
    rm -f /tmp/pandoc.deb

    # install yq (YAML query)
    YQ_VERSION="3.3.0"
    YQ_SHA256SUM="e70e482e7ddb9cf83b52f5e83b694a19e3aaf36acf6b82512cbe66e41d569201"
    curl -L -o /tmp/yq https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64
    echo "$YQ_SHA256SUM  /tmp/yq" | sha256sum -c -
    mv /tmp/yq /usr/local/bin/yq
    chmod -R a+rx /usr/local/bin/yq

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
