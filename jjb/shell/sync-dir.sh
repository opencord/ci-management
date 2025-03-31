#!/usr/bin/env bash

# Copyright 2019-2024 Open Networking Foundation (ONF) and the ONF Contributors
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

# sync-dir.sh - run build step then sync a directory to a remote server
set -eu -o pipefail

# when not running under Jenkins, use current dir as workspace, a blank project
# name
WORKSPACE=${WORKSPACE:-.}

# run the build command
$BUILD_COMMAND

# sync the files to the target
rsync -rvzh --delete-after --exclude=.git "$WORKSPACE/$BUILD_OUTPUT_PATH/$GERRIT_BRANCH" \
    "$SYNC_TARGET_SERVER:$SYNC_TARGET_PATH/$GERRIT_BRANCH"
# Parent dir index.html will only be created on master build.
rsync -vzh "$WORKSPACE/$BUILD_OUTPUT_PATH/index.html" \
    "$SYNC_TARGET_SERVER:$SYNC_TARGET_PATH/index.html" || true
