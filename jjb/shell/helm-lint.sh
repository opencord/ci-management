#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2020-2022 Open Networking Foundation <info@opennetworking.org>
# SPDX-License-Identifier: Apache-2.0

set -eu -o pipefail

# Setup helm and external repos
helm repo add stable https://charts.helm.sh/stable
helm repo add rook-release https://charts.rook.io/release
helm repo add cord https://charts.opencord.org

git clone ssh://jenkins@gerrit.opencord.org:29418/helm-repo-tools.git
./helm-repo-tools/helmlint.sh clean
echo "*.lock" >> .gitignore

# Specify the remote branch to compare against
export COMPARISON_BRANCH="origin/$GERRIT_BRANCH"
./helm-repo-tools/chart_version_check.sh

# Check for chart version conflicts by building the repo (don't upload)
git clone ssh://jenkins@gerrit.opencord.org:29418/aether-charts-repo.git
./helm-repo-tools/helmrepo.sh
