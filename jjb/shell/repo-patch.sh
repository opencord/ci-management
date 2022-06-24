#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2018-2022 Open Networking Foundation
# SPDX-License-Identifier: Apache-2.0

# repo-patch.sh
# downloads a patch to within an already checked out repo tree

set -eu -o pipefail

# verify that we have repo installed
command -v repo >/dev/null 2>&1 || { echo "repo not found, please install it" >&2; exit 1; }

echo "BASEDIR: ${BASEDIR}"
echo "GERRIT_PROJECT: ${GERRIT_PROJECT}"
echo "GERRIT_CHANGE_NUMBER: ${GERRIT_CHANGE_NUMBER}"
echo "GERRIT_PATCHSET_NUMBER: ${GERRIT_PATCHSET_NUMBER}"

pushd "${BASEDIR}"
echo "Checking out a patchset with repo, using repo version:"
repo version

PROJECT_PATH=$(xmllint --xpath "string(//project[@name=\"${GERRIT_PROJECT}\"]/@path)" .repo/manifests/default.xml)

if [ -z "$PROJECT_PATH" ]
then
  echo "WARNING: Project not in repo! Not downloading the changeset."
else
  echo "Project Path: $PROJECT_PATH"
  repo download "${PROJECT_PATH}" "$GERRIT_CHANGE_NUMBER/${GERRIT_PATCHSET_NUMBER}"
fi

popd
