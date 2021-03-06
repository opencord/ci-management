#!/bin/bash

# vim: ts=4 sw=4 sts=4 et tw=72 :

# force any errors to cause the script and job to end in failure
set -xeu -o pipefail

ensure_kernel_install() {
    # Workaround for mkinitrd failing on occassion.
    # On CentOS 7 it seems like the kernel install can fail it's mkinitrd
    # run quietly, so we may not notice the failure. This script retries for a
    # few times before giving up.
    initramfs_ver=$(rpm -q kernel | tail -1 | sed "s/kernel-/initramfs-/")
    grub_conf="/boot/grub/grub.conf"
    # Public cloud does not use /boot/grub/grub.conf and uses grub2 instead.
    if [ ! -e "$grub_conf" ]; then
        echo "$grub_conf not found. Using Grub 2 conf instead."
        grub_conf="/boot/grub2/grub.cfg"
    fi

    for i in $(seq 3); do
        if grep "$initramfs_ver" "$grub_conf"; then
            break
        fi
        echo "Kernel initrd missing. Retrying to install kernel..."
        yum reinstall -y kernel
    done
    if ! grep "$initramfs_ver" "$grub_conf"; then
        cat /boot/grub/grub.conf
        echo "ERROR: Failed to install kernel."
        exit 1
    fi
}

ensure_ubuntu_install() {
    # Workaround for mirrors occassionally failing to install a package.
    # On Ubuntu sometimes the mirrors fail to install a package. This wrapper
    # checks that a package is successfully installed before moving on.

    packages=($@)

    for pkg in "${packages[@]}"
    do
        # Retry installing package 5 times if necessary
        for i in {0..5}
        do
            if [ "$(dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -c "ok installed")" -eq 0 ]; then
                apt-cache policy "$pkg"
                apt-get install "$pkg"
                continue
            else
                echo "$pkg already installed."
                break
            fi
        done
    done
}

rh_systems() {
    # Handle the occurance where SELINUX is actually disabled
    SELINUX=$(grep -E '^SELINUX=(disabled|permissive|enforcing)$' /etc/selinux/config)
    MODE=$(echo "$SELINUX" | cut -f 2 -d '=')
    case "$MODE" in
        permissive)
            echo "************************************"
            echo "** SYSTEM ENTERING ENFORCING MODE **"
            echo "************************************"
            # make sure that the filesystem is properly labelled.
            # it could be not fully labeled correctly if it was just switched
            # from disabled, the autorelabel misses some things
            # skip relabelling on /dev as it will generally throw errors
            restorecon -R -e /dev /

            # enable enforcing mode from the very start
            setenforce enforcing

            # configure system for enforcing mode on next boot
            sed -i 's/SELINUX=permissive/SELINUX=enforcing/' /etc/selinux/config
        ;;
        disabled)
            sed -i 's/SELINUX=disabled/SELINUX=permissive/' /etc/selinux/config
            touch /.autorelabel

            echo "*******************************************"
            echo "** SYSTEM REQUIRES A RESTART FOR SELINUX **"
            echo "*******************************************"
        ;;
        enforcing)
            echo "*********************************"
            echo "** SYSTEM IS IN ENFORCING MODE **"
            echo "*********************************"
        ;;
    esac

    # Allow jenkins access to alternatives command to switch java version
    cat <<EOF >/etc/sudoers.d/89-jenkins-user-defaults
Defaults:jenkins !requiretty
jenkins ALL = NOPASSWD: /usr/sbin/alternatives
EOF

    echo "---> Updating operating system"
    yum clean all
    yum install -y deltarpm
    yum update -y

    ensure_kernel_install

    # add in components we need or want on systems
    echo "---> Installing base packages"
    yum install -y @base https://dl.fedoraproject.org/pub/epel/epel-release-latest-7.noarch.rpm
    # separate group installs from package installs since a non-existing
    # group with dnf based systems (F21+) will fail the install if such
    # a group does not exist
    yum install -y unzip xz puppet git git-review perl-XML-XPath
    yum install -y python-{devel,virtualenv}
    yum install -y python3-{devel,setuptools,pip}

    # All of our systems require Java (because of Jenkins)
    # Install all versions of the OpenJDK devel but force 1.7.0 to be the
    # default

    echo "---> Configuring OpenJDK"
    yum install -y 'java-*-openjdk-devel'

    FACTER_OS=$(/usr/bin/facter operatingsystem)
    FACTER_OSVER=$(/usr/bin/facter operatingsystemrelease)
    case "$FACTER_OS" in
        Fedora)
            if [ "$FACTER_OSVER" -ge "21" ]
            then
                echo "---> not modifying java alternatives as OpenJDK 1.7.0 does not exist"
            else
                alternatives --set java /usr/lib/jvm/jre-1.7.0-openjdk.x86_64/bin/java
                alternatives --set java_sdk_openjdk /usr/lib/jvm/java-1.7.0-openjdk.x86_64
            fi
        ;;
        RedHat|CentOS)
            if [ "$(echo "$FACTER_OSVER" | cut -d'.' -f1)" -ge "7" ]
            then
                echo "---> not modifying java alternatives as OpenJDK 1.7.0 does not exist"
            else
                alternatives --set java /usr/lib/jvm/jre-1.7.0-openjdk.x86_64/bin/java
                alternatives --set java_sdk_openjdk /usr/lib/jvm/java-1.7.0-openjdk.x86_64
            fi
        ;;
        *)
            alternatives --set java /usr/lib/jvm/jre-1.7.0-openjdk.x86_64/bin/java
            alternatives --set java_sdk_openjdk /usr/lib/jvm/java-1.7.0-openjdk.x86_64
        ;;
    esac

    ########################
    # --- START LFTOOLS DEPS

    # Used by various scripts to push patches to Gerrit
    yum install -y git-review

    # Needed to parse OpenStack commands used by opendaylight-infra stack commands
    # to initialize Heat template based systems.
    yum install -y jq

    # Used by lftools scripts to parse XML
    yum install -y xmlstarlet

     # Install Shellcheck from archive
     SHELLCHECK_VERSION="v0.6.0"
     SHELLCHECK_SHA256SUM="95c7d6e8320d285a9f026b5241f48f1c02d225a1b08908660e8b84e58e9c7dce"
     curl -L -o /tmp/shellcheck.tar.xz https://github.com/koalaman/shellcheck/releases/download/${SHELLCHECK_VERSION}/shellcheck-${SHELLCHECK_VERSION}.linux.x86_64.tar.xz
     echo "$SHELLCHECK_SHA256SUM  /tmp/shellcheck.tar.xz" | sha256sum -c -
     pushd /tmp
     tar -xJvf shellcheck.tar.xz
     cp shellcheck-${SHELLCHECK_VERSION}/shellcheck /usr/local/bin/shellcheck
     chmod a+x /usr/local/bin/shellcheck
     popd

    # --- END LFTOOLS DEPS
    ######################

    # install haveged to avoid low entropy rejecting ssh connections
    yum install -y haveged
    systemctl enable haveged.service
}

ubuntu_systems() {
    # Ignore SELinux since slamming that onto Ubuntu leads to
    # frustration

    # Allow jenkins access to update-alternatives command to switch java version
    cat <<EOF >/etc/sudoers.d/89-jenkins-user-defaults
Defaults:jenkins !requiretty
jenkins  ALL = NOPASSWD: /usr/sbin/update-alternatives, /usr/sbin/update-java-alternatives

EOF

    export DEBIAN_FRONTEND=noninteractive
    cat <<EOF >> /etc/apt/apt.conf
APT {
  Get {
    Assume-Yes "true";
    allow-change-held-packages "true";
    allow-downgrades "true";
    allow-remove-essential "true";
  };
};

Dpkg::Options {
  "--force-confdef";
  "--force-confold";
};

EOF

    # Add hostname to /etc/hosts to fix 'unable to resolve host' issue with sudo
    sed -i "/127.0.0.1/s/$/ $(hostname)/" /etc/hosts

    echo "---> Updating operating system"

    # added 2019-09-20 as apt-add-repository and software-properties-common weren't working
    cat <<EOF >/etc/apt/sources.list.d/packer.list
# created by packer
deb http://us.archive.ubuntu.com/ubuntu $(lsb_release -sc) main universe restricted multiverse

EOF

    # remove these as the fix seems to be broken now? zdw, 2019-09-20
    # # Change made 2018-07-09 by zdw
    # # per discussion on #lf-releng, the upstream Ubuntu image changed to be
    # # missing add-apt-repository, so the next command failed.
    # apt-get update -m
    # # added 2019-09-20, sometimes upstream repos are broken w/this package, try to determine why
    # apt-cache madison software-properties-common
    # apt-get install -y software-properties-common

    # add additional repositories
    # add-apt-repository "deb http://us.archive.ubuntu.com/ubuntu $(lsb_release -sc) main universe restricted multiverse"

    echo "---> Installing base packages"
    apt-get clean
    apt-get update -m
    apt-get upgrade -m
    apt-get dist-upgrade -m

    apt-get update -m
    ensure_ubuntu_install unzip xz-utils puppet git libxml-xpath-perl

    # Deprecated - updating to corretto Java distro, 2019-07-22, zdw
    # install Java 7
    # echo "---> Configuring OpenJDK"
    # FACTER_OSVER=$(/usr/bin/facter operatingsystemrelease)
    # case "$FACTER_OSVER" in
    #     14.04)
    #         apt-get install openjdk-7-jdk
    #         # make jdk8 available
    #         add-apt-repository -y ppa:openjdk-r/ppa
    #         apt-get update
    #         # We need to force openjdk-8-jdk to install
    #         apt-get install openjdk-8-jdk
    #         # make sure that we still default to openjdk 7
    #         update-alternatives --set java /usr/lib/jvm/java-7-openjdk-amd64/jre/bin/java
    #         update-alternatives --set javac /usr/lib/jvm/java-7-openjdk-amd64/bin/javac
    #     ;;
    #     16.04)
    #         apt-get install openjdk-8-jdk
    #     ;;
    #     *)
    #         echo "---> Unknown Ubuntu version $FACTER_OSVER"
    #         exit 1
    #     ;;
    # esac
    ########################

    echo "---> Configuring Corretto JDK Distribution"
    # instructions: https://docs.aws.amazon.com/corretto/latest/corretto-8-ug/generic-linux-install.html
    # install prereqs

    cat << EOF | base64 -d > /tmp/corretto-apt-key.gpg
LS0tLS1CRUdJTiBQR1AgUFVCTElDIEtFWSBCTE9DSy0tLS0tClZlcnNpb246IEdudVBHIH
YyLjAuMjIgKEdOVS9MaW51eCkKCm1RSU5CRjNwU2hrQkVBREp6Z2xlaFFERmxjMSs5VkZ1
YlZQenBxOFpZdHptSmtOamYwOXNjT1V6YUtaT20zQXIKbVBoOVJ1Zms0bUI3dDFMUDRKZU
hBS0FTMTdnZ0NIR1Z4UkdYQUFROUxhZjhpYlg0U2lGTzNFaHl5bDNzbXVGZgpaaGV4Qm52
Yzd2UmM0RVVsS3FhckNRUlVsYXJhRE9ybXE3V2JoWGR2Q2djNHUydUJMd1VqQWQzUEhRVU
J5QVp3CmxzRVF6cFFuZWhOb21qckUwcE82bXM5QWhtcGJYbGYveXIxNEVYdmxvNGxUZDhR
VWR2UytBT0NZZnJIYjlXR08KSUVzeXlEdXp1ZjJnclYvUUZwb2kwVkJoVEN5aU5ZWGxhMk
FmQ3JlTUdsT0NZc2p3MW5VOTNPeUFxRjNTYVRPQwpvNTJ5cnpjYjJOcGJCRHdSWE9ITndl
MW1kK0RiUndFZmthV3I1STkxRnFScGdFZWF3cXl4WTFtaUpSSGR1aHN6CldUZ1RNQkYvRV
FmbVRzcEQyWUJYL0JqTkpUcmREWFl2QUNYOHNsVlYvdkJucGkrZEVwVkVLM2hoMjFpajk5
MVMKbHY4WW9Gbm9DN1hQNDRDN1dOcFZRcEdXOVpXcG5qTEN2bTNETUtXMHIzVmZiM1hEWW
huSEkxUTE0UHhuMGN3Zgp4MUwyUkE0ZG95V2QxVFJaQkZCZTJmMHZTa1pUMFlGYWliS2FL
aTZBa0RJTVUvK3UrL2Uzd1diWVhxenNTSVRqCmZmTWtwTU1OU3d4Ym04SnFuc3VkanV6ZE
VzWUFpQlVjRk13V3lzUURjeXU2M3VuMk9tTEtMZkt4eTE5dkNwUzEKOG1rTnk5NUp1TzRq
WnR1K0lpaW52U1NqbGJKbXNsdTN1SzMvY1RSc1dhQjdCUnRIZXdFN1N1Z01Pd0FSQVFBQg
p0RWhCYldGNmIyNGdVMlZ5ZG1salpYTWdURXhESUNoQmJXRjZiMjRnUTI5eWNtVjBkRzhn
Y21Wc1pXRnpaU2tnClBHTnZjbkpsZEhSdkxYUmxZVzFBWVcxaGVtOXVMbU52YlQ2SkFqRU
VFd0VDQUJzRkFsM3BTaGtDR3k4RkNRbG0KQVlBRkZRZ0tDUXNDSGdFQ0Y0QUFDZ2tRb1NK
VUtyQlBKT09KRGcvNkFxbW50YXhEV1g2cWZSKyswcXd0RDlMcAp2Z09ORnZBKzlBWVFlR3
Q3T1g3OU8vU1NQeTk3S3ZuNkRZUkJkZWxTaFRBSDYwRGJYQ1VzNDJzSVJGcVJqbUhZCkhm
SWdPa1VKaldvSno5b1FuWSttekFLYk9vaENyUitZSXZ5Q2VnRmIwZGJvRGFxU1E0dzY4K2
QxaXM3TDg0cHoKWkIyajBuclFEYkZpaFBtUitlcGZIa0xVR0d5d3VaSENkRUZmRDhuWE1P
SmVWYmdTemY3VmhsOFpyeWRJa1pUSQo3YUFTRzVNa0RPL0d1VnBFR1FZQW5IOWgvanpKbG
ZVS25kc3dDNlVGY001T2wwN3BEUGRIVkJBaTlxMVN5eERlCnVTUzFOZ0RXN09XN3pncEIr
NC9QclpLS2lFUC9mQkFXYTluRlNMd1RhTWRzb2FBdVFBbW1nYnFZZnkzWFhLSzcKSUJhS1
NuSnBRRHZOYjB2bVhKRVkzcVgyQmZoMHAxS0NlYVFoWXdJSmk4clBRV0MyNGZpTFk5YmRD
SWxrYmJQUQpDU05PRXE5blVXUmc5S2JVR21kL1BXU2tUNkpoZXlxM0JaQkYxWVBZRXQ4by
9sNDM3SEhkMDhsUkVxSDBzYW5hCkhiNzJHWlRpMlJVck5CQnA1QzFlOE1xbGxYRTZSS21y
aTJtMFRTQkhSNUM0WkxJSTlkdXlBODM5ZFlJQTRLR1UKbm1ldFpja3VSdXdIRm1kMy9ZV3
RNRWZuNDdVZWR6aFZUMTZ6M092QmlwSFUxQkt6TEdjdlVGWHJVS3ZwSlFsaApkTlBVUWgr
d2I5MUV6SXRqa0o5Nm0rTis4MWlRZE4zeWQ4Y0UzOE5UQThiK1FjN3RtVFl4d05aeGN2MT
ZGeExBCnkyVmhLYzA5QThSd1NJNjl2RHM9Cj1aTlJICi0tLS0tRU5EIFBHUCBQVUJMSUMg
S0VZIEJMT0NLLS0tLS0K
EOF

    apt-key add /tmp/corretto-apt-key.gpg
    add-apt-repository -y 'deb https://apt.corretto.aws stable main'
    apt-get update

    apt-get install -y java-1.8.0-amazon-corretto-jdk java-11-amazon-corretto-jdk

    # Set default version to be Java8
    update-java-alternatives --set java-1.8.0-amazon-corretto

    # Set default version to be Java11
    # update-java-alternatives --set java-11-amazon-corretto

    ########################
    # --- START LFTOOLS DEPS

    # Used by various scripts to push patches to Gerrit
    # ensure_ubuntu_install git-review

    # Needed to parse OpenStack commands used by opendaylight-infra stack commands
    # to initialize Heat template based systems.
    ensure_ubuntu_install jq

    # Used by lftools scripts to parse XML
    ensure_ubuntu_install xmlstarlet

    # Change made by zdw on 2018-04-12
    # hackage.haskell.org was down, talked to zxiiro on #lf-releng and he recommended
    # pulling down the packages in a way similar to this ansible role, rather than using cabal:
    # https://github.com/lfit/ansible-roles-shellcheck-install/blob/master/tasks/main.yml

    SHELLCHECK_VERSION="v0.6.0"
    SHELLCHECK_SHA256SUM="95c7d6e8320d285a9f026b5241f48f1c02d225a1b08908660e8b84e58e9c7dce"
    curl -L -o /tmp/shellcheck.tar.xz https://github.com/koalaman/shellcheck/releases/download/${SHELLCHECK_VERSION}/shellcheck-${SHELLCHECK_VERSION}.linux.x86_64.tar.xz
    echo "$SHELLCHECK_SHA256SUM  /tmp/shellcheck.tar.xz" | sha256sum -c -
    pushd /tmp
    tar -xJvf shellcheck.tar.xz
    cp shellcheck-${SHELLCHECK_VERSION}/shellcheck /usr/local/bin/shellcheck
    chmod a+x /usr/local/bin/shellcheck
    popd

    # --- END LFTOOLS DEPS
    ######################

    # install haveged to avoid low entropy rejecting ssh connections
    apt-get install haveged
    update-rc.d haveged defaults

    # disable unattended upgrades & daily updates
    echo '---> Disabling automatic daily upgrades'
    sed -ine 's/"1"/"0"/g' /etc/apt/apt.conf.d/10periodic
    echo 'APT::Periodic::Unattended-Upgrade "0";' >> /etc/apt/apt.conf.d/10periodic
}

all_systems() {
    # Do any Distro specific installations here
    echo "Checking distribution"
    FACTER_OS=$(/usr/bin/facter operatingsystem)
    case "$FACTER_OS" in
        *)
            echo "---> $FACTER_OS found"
            echo "No extra steps for $FACTER_OS"
        ;;
    esac
}

echo "---> Attempting to detect OS"
# upstream cloud images use the distro name as the initial user
ORIGIN=$(if [ -e /etc/redhat-release ]
    then
        echo redhat
    else
        echo ubuntu
    fi)
#ORIGIN=$(logname)

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
        # Kill the build for unhandled distributions
        echo "---> Unknown operating system" 1>&2
        exit 1
    ;;
esac

# execute steps for all systems
all_systems
