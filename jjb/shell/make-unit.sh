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

# basic init for go-based testing, specific to the Jenkins CI setup
mkdir -p ~/go/src
export GOPATH=~/go
export PATH=$PATH:/usr/lib/go-1.10/bin:/usr/local/go/bin:~/go/bin

# when not running under Jenkins, use current dir as workspace and test path
WORKSPACE=${WORKSPACE:-.}
# Path to where the Makefile is within the checkout
UNIT_TEST_PATH=${UNIT_TEST_PATH:-.}

# combined path
test_path="$WORKSPACE/$UNIT_TEST_PATH"

# Use "test" as the default target, can be a space separated list
UNIT_TEST_MAKE_TARGETS=${UNIT_TEST_MAKE_TARGETS:-test}

# default to fail on the first test that fails
UNIT_TEST_KEEP_GOING=${UNIT_TEST_KEEP_GOING:-false}

if [ ! -f "$test_path/Makefile" ]; then
  echo "Makefile not found at $test_path!"
  exit 1
else
  pushd "$test_path"

  # we want to split the make targets apart, so:
  # shellcheck disable=SC2086
  if [ "$UNIT_TEST_KEEP_GOING" = "true" ]; then
    make -k $UNIT_TEST_MAKE_TARGETS
  else
    make $UNIT_TEST_MAKE_TARGETS
  fi

  popd
fi
