#!/usr/bin/env bash

# Copyright 2018-present Open Networking Foundation
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

# versiontag.sh
# Tags a git commit with the SemVer version discovered within the commit,
# if the tag doesn't already exist. Ignore non-SemVer commits.

set -eu -o pipefail

NEW_VERSION=""
VERSIONFILE=""

releaseversion=0

# find the version string in the repo, read into NEW_VERSION
# Add additional places NEW_VERSION could be found to this function
function read_version {
  if [ -f "VERSION" ]
  then
    NEW_VERSION=$(head -n1 "VERSION")
    VERSIONFILE="VERSION"
    echo "New version: $NEW_VERSION"
  else
    echo "ERROR: No versioning file found!"
    exit 1
  fi
}

# check if the version is already a tag in git
function is_git_tag_duplicated {
  for existing_tag in $(git tag)
  do
    if [ "$NEW_VERSION" = "$existing_tag" ]
    then
      echo "ERROR: Duplicate tag: $existing_tag"
      exit 2
    fi
  done
}

# check if the version is a released version
function check_if_releasever {
  if [[ "$NEW_VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]
  then
    echo "Version string '$NEW_VERSION' in '$VERSIONFILE' is a SemVer released version!"
    releaseversion=1
  else
    echo "Version string '$NEW_VERSION' in '$VERSIONFILE' is not a SemVer released version, skipping."
  fi
}

# create a git tag
function create_git_tag {
  echo "Creating git tag: $NEW_VERSION"
  git checkout "$GERRIT_PATCHSET_REVISION"
  git tag -a "$NEW_VERSION" -m "Tagged by CORD Jenkins version-tag job: $BUILD_NUMBER, for Gerrit patchset: $GERRIT_CHANGE_NUMBER"
  git push origin "$NEW_VERSION"
}

echo "Checking git repo with remotes:"
git remote -v

echo "Branches:"
git branch -v

echo "Existing git tags:"
git tag -n

read_version
is_git_tag_duplicated
check_if_releasever

if $releaseversion
then
  create_git_tag
fi

exit 0
