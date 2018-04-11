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

# repopatch.sh
# downloads a patch to within an already checked out repo tree

set -eu -o pipefail

# verify that we have repo installed
command -v repo >/dev/null 2>&1 || { echo "repo not found, please install it" >&2; exit 1; }

echo "DESTINATION_DIR: ${DESTINATION_DIR}"
echo "GERRIT_PROJECT: ${GERRIT_PROJECT}"
echo "GERRIT_CHANGE_NUMBER: ${GERRIT_CHANGE_NUMBER}"
echo "GERRIT_PATCHSET_NUMBER: ${GERRIT_PATCHSET_NUMBER}"

pushd "${DESTINATION_DIR}"
echo "Checking out a patchset with repo, using repo version:"
repo version

PROJECT_PATH=$(xmllint --xpath "string(//project[@name=\"${GERRIT_PROJECT}\"]/@path)" .repo/manifest.xml)
echo "Project Path: $PROJECT_PATH"

repo download "${PROJECT_PATH}" "$GERRIT_CHANGE_NUMBER/${GERRIT_PATCHSET_NUMBER}"
popd

