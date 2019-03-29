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

# xos-unit.sh - perform a unit test on XOS or synchronizers
set -e -o pipefail

# when not running under Jenkins, use current dir as workspace, and 'xos' as GERRIT_PROJECT
WORKSPACE=${WORKSPACE:-.}
GERRIT_PROJECT=${GERRIT_PROJECT:-xos}

# create python virtual env
export XOS_DIR=${WORKSPACE}/cord/orchestration/xos
$XOS_DIR/scripts/setup_venv.sh
source venv-xos/bin/activate

# find the path to the project that is checked out
PROJECT_PATH=$(xmllint --xpath "string(//project[@name=\"$GERRIT_PROJECT\"]/@path)" cord/.repo/manifest.xml)

if [ "$GERRIT_PROJECT" = 'xos' ] ; then
  pushd "$WORKSPACE/cord/$PROJECT_PATH"
else
  pushd "$WORKSPACE/cord/$PROJECT_PATH/xos"
fi

if [ -f Makefile ]; then
  make test
else
  echo "Checking Migrations"
  if [ "$GERRIT_PROJECT" = 'xos' ] ; then
    xos-migrate -r $WORKSPACE/cord -s core --check
  else
    xos-migrate -r $WORKSPACE/cord -s $GERRIT_PROJECT --check
  fi

  echo "Performing nose2 tests"
  nose2 --verbose --coverage-report xml --coverage-report term --junit-xml
fi

popd
