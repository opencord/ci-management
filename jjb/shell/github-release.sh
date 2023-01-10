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

## -----------------------------------------------------------------------
## Intent:
##   Display available commands and paths for golang.
##   A github release job is failing due to a command being mia.
##
## Note: This function is being used for side effects, when commands
## are unavailable to query for version info, fail the job.
## -----------------------------------------------------------------------
## 05:02:50 bash: go: command not found
## 05:03:00 Unable to find image 'voltha/voltha-ci-tools:2.4.0-golang' locally
## -----------------------------------------------------------------------
function displayCommands()
{
    set +euo pipefail

    echo
    echo "Installed /usr/lib/go"
    echo "-----------------------------------------------------------------------"
    find /usr/lib -mindepth 1 -maxdepth 1 -name 'go-*' -print || /bin/true

    echo
    echo "Go commands in \$PATH"
    echo "-----------------------------------------------------------------------"
    which -a go || /bin/true

    echo
    echo "VERSIONS:"
    echo "-----------------------------------------------------------------------"
    git --version
    go version

    set -euo pipefail
    return
}

##----------------##
##---]  MAIN  [---##
##----------------##
set -eu -o pipefail

# when not running under Jenkins, use current dir as workspace and a blank
# project name
WORKSPACE=${WORKSPACE:-.}
GERRIT_PROJECT=${GERRIT_PROJECT:-}

# Github organization (or user) this project is published on.  Project name should
# be the same on both Gerrit and GitHub
GITHUB_ORGANIZATION=${GITHUB_ORGANIZATION:-}

# glob pattern relative to project dir matching release artifacts
ARTIFACT_GLOB=${ARTIFACT_GLOB:-"release/*"}

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

displayCommands

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

  pushd "$release_path"
  set -x

  # Release description is sanitized version of the log message
  RELEASE_DESCRIPTION="$(git log -1 --pretty=%B | tr -dc "[:alnum:]\n\r\.\[\]\:\-\\\/\`\' ")"

  # build the release, can be multiple space separated targets
  # shellcheck disable=SC2086
  make "$RELEASE_TARGETS"

  # Copy artifacts into the release temp dir
  # shellcheck disable=SC2086
  cp "$ARTIFACT_GLOB" "$RELEASE_TEMP"

  # create release
  echo "Creating Release: $GERRIT_PROJECT - $GIT_VERSION"
  github-release release \
    --user "$GITHUB_ORGANIZATION" \
    --repo "$GERRIT_PROJECT" \
    --tag  "$GIT_VERSION" \
    --name "$GERRIT_PROJECT - $GIT_VERSION" \
    --description "$RELEASE_DESCRIPTION"

  # handle release files
  pushd "$RELEASE_TEMP"

    # Generate and check checksums
    sha256sum -- * > checksum.SHA256
    sha256sum -c < checksum.SHA256

    echo "Checksums:"
    cat checksum.SHA256

    # upload all files to the release
    for rel_file in *
    do
      echo "Uploading file: $rel_file"
      github-release upload \
        --user "$GITHUB_ORGANIZATION" \
        --repo "$GERRIT_PROJECT" \
        --tag  "$GIT_VERSION" \
        --name "$rel_file" \
        --file "$rel_file"
    done
    set +x
  popd

  popd
fi

# [SEE ALSO]
# -----------------------------------------------------------------------
# https://www.shellcheck.net/wiki/SC2236
# -----------------------------------------------------------------------

# [EOF]
