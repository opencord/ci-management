#!/bin/bash
# -----------------------------------------------------------------------
# Copyright 2024 Open Networking Foundation Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http:#www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# -----------------------------------------------------------------------
# SPDX-FileCopyrightText: 2024 Open Networking Foundation Contributors
# SPDX-License-Identifier: Apache-2.0
# -----------------------------------------------------------------------#
# Source: /sandbox/ci-management/jjb/pipeline/voltha/software-upgrades.groovy
# -----------------------------------------------------------------------

## -----------------------------------------------------------------------
## Intent: Shell snippet from software-upgrades.groovy for testing
## -----------------------------------------------------------------------
function gather_comp_deploy()
{
    local -n ref=$1; shift
    local url="$1"; shift

    readarray -t buffer < <(git ls-remote --refs --tags "$url" \
                                | cut --delimiter='/' --fields=3 \
                                | tr '-' '~' \
                                | sort --version-sort \
                                | tail --lines=2 \
                                | head -n 1 \
                                | sed 's/v//' \
                           )
    ref=("${buffer[@]}")
    return
}

## -----------------------------------------------------------------------
## Intent: Shell snippet from software-upgrades.groovy for testing
## -----------------------------------------------------------------------
function gather_comp_test()
{
    local -n ref=$1; shift
    local url="$1"; shift

    readarray -t buffer < <(git ls-remote --refs --tags "$url" \
                                | cut --delimiter='/' --fields=3 \
                                | tr '-' '~' \
                                | sort --version-sort \
                                | tail --lines=1 \
                                | sed 's/v//'
                           )
    ref=("${buffer[@]}")
    return
}

: # ($?==0) for source script

# [EOF]
