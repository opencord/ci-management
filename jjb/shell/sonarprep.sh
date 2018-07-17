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

# sonarprep.sh - prep project for running sonarqube
set -eu -o pipefail

# run build commands if not blank
if [ ! -z "$SONAR_PREP_COMMANDS" ]
then
  echo "# Running SONAR_PREP_COMMANDS: '$SONAR_PREP_COMMANDS'"
  $SONAR_PREP_COMMANDS
else
  echo "# No preparation commands set in SONAR_PREP_COMMANDS"
fi

echo "# pylint version"
pylint --version

