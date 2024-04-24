#!/usr/bin/env bash
# -----------------------------------------------------------------------
# Copyright 2018-2024 Open Networking Foundation Contributors
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
# -----------------------------------------------------------------------
# SPDX-FileCopyrightText: 2018-2024 Open Networking Foundation Contributors
# SPDX-License-Identifier: Apache-2.0
# -----------------------------------------------------------------------
# Intent:
# builds (with make) and uploads release artifacts (binaries, etc.) to github
# given a tag also create checksums files and release notes from the commit
# message
# -----------------------------------------------------------------------

## ---------------------------------------------------------------------------
## Intent: Display program usage
## ---------------------------------------------------------------------------
function usage()
{
    cat <<EOH

Usage: $0
Usage: make [options] [target] ...
  --help                      This mesage
  --pac                       Personal Access Token (path to containing file or a string)
  --repo-name                 ex: voltctl
  --repo-org                  ex: opencord
  --release-notes [f]         Release notes are passed by file argument

[DEBUG]
  --gen-version               Generate a random release version string.
  --git-hostname              Git server hostname (default=github.com)
  --version-file [f]          Read version string from local version file (vs env var)
                              Assume ./VERSION if [f] not passed
[MODES]
  --debug                     Enable script debug mode
  --draft                     Create a draft release (vs published)
  --dry-run                   Simulation mode
  --todo                      Display future enhancement list

All other arguments are pass-through to the gh command.

Usage: $0 --draft --repo-org opencord --repo-name voltctl --git-hostname github.com --pac ~/access.pac

# Troubleshoot credentials:
Usage: $0 --pac ~/.access.pac --verify

EOH

    return
}

: # assign $?=0 for source $script

# [EOF]
