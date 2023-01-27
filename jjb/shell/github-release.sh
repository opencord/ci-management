#!/usr/bin/env bash
# -----------------------------------------------------------------------
# Copyright 2018-2023 Open Networking Foundation (ONF) and the ONF Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# github-release.sh
# builds (with make) and uploads release artifacts (binaries, etc.) to github
# given a tag also create checksums files and release notes from the commit
# message
# -----------------------------------------------------------------------

##-------------------##
##---]  GLOBALS  [---##
##-------------------##
declare -g ARGV="$*"            # archive for display
declare -g SCRIPT_VERSION='1.0' # git changeset needed
declare -g TRACE=0              # uncomment to set -x
declare -g VERBOSE=0            # trace github-release calls

declare -g RELEASE_TEMP

##--------------------##
##---]  INCLUDES  [---##
##--------------------#
# declare -g pgmdir="${0%/*}" # dirname($script)
echo "** ${0}: PWD=$(/bin/pwd)"

# -----------------------------------------------------------------------
# Jenkins must have checked out/copied the script -vs- repository
# -----------------------------------------------------------------------
# 17:56:27 [github-release_voltctl] $ /usr/bin/env bash /tmp/jenkins1043949650153731384.sh
# 17:56:27 ** /tmp/jenkins1043949650153731384.sh: PWD=/w/workspace/github-release_voltctl
# 17:56:27   5120009      4 drwxrwxr-x   4 jenkins  jenkins      4096 Jan 25 22:56 .
# 17:56:27   5120010      4 drwxrwxr-x   9 jenkins  jenkins      4096 Jan 25 22:56 ./voltctl
# 17:56:27   5120036      4 drwxrwxr-x   2 jenkins  jenkins      4096 Jan 25 22:56 ./voltctl@tmp
# 17:56:27 /tmp/jenkins1043949650153731384.sh: line 44: /tmp/common/common.sh: No such file or directory
# -----------------------------------------------------------------------

# declare -a common_args=()
# common_args+=('--common-args-begin--')
# common_args+=('--traputils')
# common_args+=('--stacktrace')
# common_args+=('--tempdir')

# shellcheck disable=SC1091
# source "${pgmdir}/common/common.sh" "${common_args[@]}"

## -----------------------------------------------------------------------
## Intent: Output a log banner to identify the running script/version.
## -----------------------------------------------------------------------
## TODO:
##   o SCRIPT_VERSION => git changeset for repo:ci-managment
##   o echo "library version: ${env."library.libName.version"}"
# -----------------------------------------------------------------------
# 14:18:38   > git fetch --no-tags --progress -- https://gerrit.opencord.org/ci-management.git +refs/heads/*:refs/remotes/origin/* # timeout=10
# 14:18:39  Checking out Revision 50f6e0b97f449b32d32ec0e02d59642000351847 (master)
# -----------------------------------------------------------------------
function banner()
{
    local iam="${0##*/}"

cat <<EOH

** -----------------------------------------------------------------------
** IAM: ${iam} :: ${FUNCNAME[0]}
** ARGV: ${ARGV}
** PWD: $(/bin/pwd)
** NOW: $(date '+%Y/%m/%d %H:%M:%S')
** VER: ${SCRIPT_VERSION:-'unknown'}
** -----------------------------------------------------------------------
EOH

    return
}

## -----------------------------------------------------------------------
## Intent:
##   Display available command versions
##   A github release job is failing due to a command being mia.
## -----------------------------------------------------------------------
function displayCommands()
{
    # which git || /bin/true
    # git --version || /bin/true
    # go version    || /bin/true
    # helm version  || /bin/true
    return
}

## -----------------------------------------------------------------------
## Intent: Display filesystem with a banner for logging
## -----------------------------------------------------------------------
function displayPwd()
{
    local iam="${0##*/}"
    echo "** ${iam}: ENTER"

cat <<EOM

** -----------------------------------------------------------------------
** IAM: $iam :: ${FUNCNAME[0]}
** PWD: $(/bin/pwd)
** -----------------------------------------------------------------------
EOM
    find . -maxdepth 1 -ls
    echo "** ${iam}: LEAVE"
    return
}

## -----------------------------------------------------------------------
## Intent:
## -----------------------------------------------------------------------
function doDebug()
{
    echo "** ${FUNCNAME[0]}: ENTER"

    echo
    echo "** PWD: $(/bin/pwd)"
    echo "** make-pre: $(/bin/ls -l)"
    echo

    declare -p ARTIFACT_GLOB
    declare -p RELEASE_TEMP

    echo
    echo "** ${FUNCNAME[0]}: ARTIFACT_GLOB=${ARTIFACT_GLOB}"
    local artifact_glob="${ARTIFACT_GLOB%/*}"
    declare -p artifact_glob

    echo "** ${FUNCNAME[0]}: PWD=$(/bin/pwd)"
    find "$artifact_glob" -print || /bin/true

    copyToRelease

    # Copy artifacts into the release temp dir
    # shellcheck disable=SC2086
    # cp -v "$ARTIFACT_GLOB" "$RELEASE_TEMP"
    # echo "rsync -rv --checksum \"$artifact_glob/.\" \"$RELEASE_TEMP/.\""
    # rsync -rv --checksum "$artifact_glob/." "$RELEASE_TEMP/."
    
    echo "** ${FUNCNAME[0]}: LEAVE"
    echo
    return
}

## -----------------------------------------------------------------------
## Intent:
## -----------------------------------------------------------------------
function copyToRelease()
{
    local iam="${FUNCNAME[0]}"
    echo "** ${iam}: ENTER"
 
    local artifact_glob="${ARTIFACT_GLOB%/*}"
    echo "** ${iam}: $(declare -p ARTIFACT_GLOB) $(declare -p artifact_glob)"

    # Copy artifacts into the release temp dir
    # shellcheck disable=SC2086
    # cp -v "$ARTIFACT_GLOB" "$RELEASE_TEMP"
    echo "rsync -rv --checksum \"$artifact_glob/.\" \"$RELEASE_TEMP/.\""
    rsync -rv --checksum "$artifact_glob/." "$RELEASE_TEMP/."

    echo "** ${iam}: RELEASE_TEMP=${RELEASE_TEMP}"
    find "$RELEASE_TEMP" -print

    echo "** ${iam}: LEAVE"
    echo
    return
}

## -----------------------------------------------------------------------
## Intent:
## -----------------------------------------------------------------------
function showGithubTool()
{
    local iam="${FUNCNAME[0]}"
    echo "** ${iam}: ENTER"

    echo "** ${iam}: which github-release: $(which -a github-release)"
    local switch
    for switch in '--version' '--help';
    do
	echo "** ${iam}: github-release ${switch}: $(github-release ${switch})"
    done
    echo "** ${iam}: LEAVE"
    echo
    return
}

## -----------------------------------------------------------------------
## Intent: Display info about a release stored on github.
## -----------------------------------------------------------------------
function showReleaseInfo()
{
    local user="$1" ; shift
    local repo="$1" ; shift
    local tag=''
    if [ $# -gt 0 ]; then
	tag="$1"  ; shift
    fi

    local iam="${FUNCNAME[0]}"
    echo "** ${iam}: ENTER"

    cat <<EOM

** -------------------------------------------------------------------
** ${iam}
** -------------------------------------------------------------------
EOM

    declare -a args=()
    [[ $VERBOSE -eq  1 ]] && args+=('--verbose')
    args+=('info')
    args+=('--user' "$user")
    args+=('--repo' "$repo")
    if [ ${#tag} -gt 0 ]; then
	args+=('--tag'  "$tag")
    fi
    
    echo "${iam}: github-relase info"
    github-release "${args[@]}"
    
    echo "** ${iam}: LEAVE"
    return
}

##----------------##
##---]  MAIN  [---##
##----------------##
set -eu -o pipefail

iam="${0##*/}"

banner

# when not running under Jenkins, use current dir as workspace and a blank
# project name
WORKSPACE=${WORKSPACE:-.}
GERRIT_PROJECT=${GERRIT_PROJECT:-}

# Github organization (or user) this project is published on.  Project name should
# be the same on both Gerrit and GitHub
GITHUB_ORGANIZATION=${GITHUB_ORGANIZATION:-}

# glob pattern relative to project dir matching release artifacts
# ARTIFACT_GLOB=${ARTIFACT_GLOB:-"release/*"} # stat -- release/* not found, literal string (?)
ARTIFACT_GLOB=${ARTIFACT_GLOB:-"release/."}

# Temporary staging directory to copy artifacts to
RELEASE_TEMP="$WORKSPACE/release"
mkdir -p "$RELEASE_TEMP"

# Use "release" as the default makefile target, can be a space separated list
RELEASE_TARGETS=${RELEASE_TARGETS:-release}


# check that we're on a semver released version, or exit
pushd "$GERRIT_PROJECT"
  GIT_VERSION=$(git tag -l --points-at HEAD)

  # match bare versions or v-prefixed golang style version
  if [[ "$GIT_VERSION" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]
  then
    echo "git has a SemVer released version tag: '$GIT_VERSION'"
    echo "Building artifacts for GitHub release."
  else
    echo "No SemVer released version tag found, exiting..."
    exit 0
  fi
popd

# Set and handle GOPATH and PATH
export GOPATH=${GOPATH:-$WORKSPACE/go}
export PATH=$PATH:/usr/lib/go-1.12/bin:/usr/local/go/bin:$GOPATH/bin

# To support golang projects that require GOPATH to be set and code checked out there
# If $DEST_GOPATH is not an empty string:
# - create GOPATH within WORKSPACE, and destination directory within
# - set PATH to include $GOPATH/bin and the system go binaries
# - move project from $WORKSPACE/$GERRIT_PROJECT to new location in $GOPATH
# - start release process within that directory

DEST_GOPATH=${DEST_GOPATH:-}
if [ -n "$DEST_GOPATH" ]; then
  mkdir -p "$GOPATH/src/$DEST_GOPATH"
  release_path="$GOPATH/src/$DEST_GOPATH/$GERRIT_PROJECT"
  mv "$WORKSPACE/$GERRIT_PROJECT" "$release_path"
else
  release_path="$WORKSPACE/$GERRIT_PROJECT"
fi

if [ ! -f "$release_path/Makefile" ]; then
  echo "Makefile not found at $release_path!"
  exit 1
else

  declare -p release_path

  # shellcheck disable=SC2015
  [[ -v TRACE ]] && { set -x; } || { set +x; } # SC2015 (shellcheck -x)

  pushd "$release_path"

  # Release description is sanitized version of the log message
  RELEASE_DESCRIPTION="$(git log -1 --pretty=%B | tr -dc "[:alnum:]\n\r\.\[\]\:\-\\\/\`\' ")"

  # build the release, can be multiple space separated targets
  # shellcheck disable=SC2086
  make "$RELEASE_TARGETS"

  # doDebug # deterine why ARTIFACT_GLOB is empty
  copyToRelease

  # Are we failing on a literal string "release/*" ?
  # cp -v "$ARTIFACT_GLOB" "$RELEASE_TEMP"
  # echo "rsync -rv --checksum \"$artifact_glob/.\" \"$RELEASE_TEMP/.\""
  # rsync -rv --checksum "$artifact_glob/." "$RELEASE_TEMP/."

  showReleaseInfo \
      "$GITHUB_ORGANIZATION"\
      "$GERRIT_PROJECT"

  showGithubTool

  cat <<EOM

** -----------------------------------------------------------------------
** Create the release:
**  1) Create initial github release with download area.
**  2) Generate checksum.SHA256 for all released files.
**  3) Upload files to complete the release.
**  4) Display released info from github.
** -----------------------------------------------------------------------
EOM

  # Usage: github-release [global options] <verb> [verb options]
  # create release
  echo "** ${iam} Creating Release: $GERRIT_PROJECT - $GIT_VERSION"
  github-release \
      --verbose \
      release \
      --user "$GITHUB_ORGANIZATION" \
      --repo "$GERRIT_PROJECT" \
      --tag  "$GIT_VERSION" \
      --name "$GERRIT_PROJECT - $GIT_VERSION" \
      --description "$RELEASE_DESCRIPTION"

  echo "** ${iam} Packaging release files"
  pushd "$RELEASE_TEMP"

    ## [TODO] Add a filecount check, detect failed source copy
    find . -mindepth 1 -maxdepth 1 ! -type d -ls

    # Generate and check checksums
    sha256sum -- * > checksum.SHA256
    sha256sum -c < checksum.SHA256

    echo
    echo "** ${iam} Checksums(checksum.SHA256):"
    cat checksum.SHA256
    echo

    # upload all files to the release
    for rel_file in *
    do
      echo "** ${iam} Uploading file: $rel_file"
      github-release \
	  upload \
          --user "$GITHUB_ORGANIZATION" \
          --repo "$GERRIT_PROJECT" \
          --tag  "$GIT_VERSION" \
          --name "$rel_file" \
          --file "$rel_file"
    done

  popd
  popd

  echo "** ${iam} Display released info"
  showReleaseInfo \
      "$GITHUB_ORGANIZATION"\
      "$GERRIT_PROJECT"\
      "$GIT_VERSION"
fi

# [SEE ALSO]
# -----------------------------------------------------------------------
# https://www.shellcheck.net/wiki/SC2236
# https://docs.github.com/en/rest/releases/releases?apiVersion=2022-11-28#create-a-release
# -----------------------------------------------------------------------

# [EOF]
