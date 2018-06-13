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

# tagcollisionreject.sh
# checks that there isn't an existing tag in the repo that has this tag

set -eu -o pipefail

NEW_VERSION=""

# find the version string in the repo, read into NEW_VERSION
# Add additional places NEW_VERSION could be found to this function
function read_version {
  if [ -f "VERSION" ]
  then
    NEW_VERSION=$(head -n1 "VERSION")
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

echo "Checking git repo with remotes:"
git remote -v

echo "Branches:"
git branch -v

echo "Existing git tags:"
git tag -n

read_version
is_git_tag_duplicated

exit 0

