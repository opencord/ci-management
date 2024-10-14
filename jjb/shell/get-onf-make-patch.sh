#!/usr/bin/env bash

# Copyright 2017-2024 Open Networking Foundation (ONF) and the ONF Contributors
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

# get-onf-make-patch.sh - Pull the patch of onf-make that triggered the job
# for testing

ONF_MAKE_SUBDIR="lf/onf-make"
PROJECT="${TEST_PROJECT:-}"
REPO="https://gerrit.opencord.org/$PROJECT"

cd $WORKSPACE
git clone "$REPO" "$PROJECT"
cd "$PROJECT"
git submodule update --init

REPO="https://gerrit.opencord.org/onf-make"

pushd "$ONF_MAKE_SUBDIR"
git fetch "$REPO" "$GERRIT_REFSPEC" && git checkout FETCH_HEAD
popd
