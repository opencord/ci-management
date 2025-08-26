#!/usr/bin/env bash

# Copyright 2020-2024 Open Networking Foundation (ONF) and the ONF Contributors
# SPDX-License-Identifier: Apache-2.0

set -eu -o pipefail

export OLD_REPO_DIR="cord-charts-repo"

# Setup helm and external repos
cat <<EOM

** -----------------------------------------------------------------------
** Configure helm and external repos
** -----------------------------------------------------------------------
EOM

helm repo add stable https://charts.helm.sh/stable
helm repo add rook-release https://charts.rook.io/release
helm repo add cord https://charts.lfbroadband.org

## Clone tool chain
git clone ssh://jenkins@gerrit.lfbroadband.org:29418/helm-repo-tools.git
./helm-repo-tools/helmlint.sh clean
echo "*.lock" >> .gitignore

# Specify the remote branch to compare against
export COMPARISON_BRANCH="origin/$GERRIT_BRANCH"
./helm-repo-tools/chart_version_check.sh  # [TODO] make lint-chart

# Configure git
git config --global user.email "do-not-reply@opennetworking.org"
git config --global user.name "Jenkins"

# Check for chart version conflicts by building the repo (don't upload)
git clone "ssh://jenkins@gerrit.lfbroadband.org:29418/$OLD_REPO_DIR.git"

./helm-repo-tools/helmrepo.sh   # [TODO] make ???

# [EOF]
