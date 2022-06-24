#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2020-2022 Open Networking Foundation <info@opennetworking.org>
# SPDX-License-Identifier: Apache-2.0

set -eu -o pipefail

echo "git version: $(git --version)"

# Variables used in this and child scripts
export PUBLISH_URL="charts.opencord.org"
export OLD_REPO_DIR="cord-charts-repo"
export NEW_REPO_DIR="chart_repo"

# Configure git
git config --global user.email "do-not-reply@opennetworking.org"
git config --global user.name "Jenkins"

# Checkout 'cord-charts-repo' repo that contains updated charts
git clone "ssh://jenkins@gerrit.opencord.org:29418/$OLD_REPO_DIR.git"

# Clone the `helm-repo-tools` which contains scripts
git clone ssh://jenkins@gerrit.opencord.org:29418/helm-repo-tools.git

# Setup helm and external repos
helm repo add stable https://charts.helm.sh/stable
helm repo add rook-release https://charts.rook.io/release
helm repo add cord https://charts.opencord.org
helm repo add elastic  https://helm.elastic.co
helm repo add kiwigrid https://kiwigrid.github.io

# Update the repo
./helm-repo-tools/helmrepo.sh

# Tag and push to git the charts repo
pushd "$OLD_REPO_DIR"

  # only update if charts are changed
  set +e
  if git diff --exit-code index.yaml > /dev/null; then
    echo "No changes to charts in patchset $GERRIT_CHANGE_NUMBER on project: $GERRIT_PROJECT, exiting."
    exit 0
  fi
  set -e

  # version tag is the current date in RFC3339 format
  NEW_VERSION=$(date -u +%Y%m%dT%H%M%SZ)

  # Add changes and create commit
  git add -A
  git commit -m "Changed by CORD Jenkins publish-helm-repo job: $BUILD_NUMBER, for project: $GERRIT_PROJECT, patchset: $GERRIT_CHANGE_NUMBER"

  # create tag on new commit
  git tag "$NEW_VERSION"

  echo "Tags including new tag:"
  git tag -n

  # push new commit and tag back into repo
  git push origin
  git push origin "$NEW_VERSION"
popd

rsync -rvzh --delete-after --exclude=.git "$OLD_REPO_DIR/" "static.opennetworking.org:/srv/sites/$PUBLISH_URL/"
