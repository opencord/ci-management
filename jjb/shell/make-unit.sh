#!/usr/bin/env bash

# Copyright 2019-present Open Networking Foundation
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

# make-unit.sh - run one or more make targets for unit testing
set -eu -o pipefail

# when not running under Jenkins, use current dir as workspace, a blank project
# name
WORKSPACE=${WORKSPACE:-.}
GERRIT_PROJECT=${GERRIT_PROJECT:-}

# Fixes to for golang projects to support GOPATH
# If $DEST_GOPATH is not an empty string:
# - set create GOPATH, and destination directory within in
# - set PATH to include $GOPATH/bin and the system go binaries
# - symlink from $WORKSPACE/$GERRIT_PROJECT to new location in $GOPATH
# - start tests in that directory

DEST_GOPATH=${DEST_GOPATH:-}
if [ ! -z "$DEST_GOPATH" ]; then
  export GOPATH=${GOPATH:-~/go}
  mkdir -p "$GOPATH/src/$DEST_GOPATH"
  export PATH=$PATH:/usr/lib/go-1.10/bin:/usr/local/go/bin:$GOPATH/bin
  test_path="$GOPATH/src/$DEST_GOPATH/$GERRIT_PROJECT"
  ln -r -s "$WORKSPACE/$GERRIT_PROJECT" "$test_path"
else
  test_path="$WORKSPACE/$GERRIT_PROJECT"
fi

# Use "test" as the default target, can be a space separated list
UNIT_TEST_TARGETS=${UNIT_TEST_TARGETS:-test}

# Default to fail on the first test that fails
UNIT_TEST_KEEP_GOING=${UNIT_TEST_KEEP_GOING:-false}

if [ ! -f "$test_path/Makefile" ]; then
  echo "Makefile not found at $test_path!"
  exit 1
else
  pushd "$test_path"

  # we want to split the make targets apart on spaces, so:
  # shellcheck disable=SC2086
  if [ "$UNIT_TEST_KEEP_GOING" = "true" ]; then
    make -k $UNIT_TEST_TARGETS
  else
    make $UNIT_TEST_TARGETS
  fi

  popd
fi
