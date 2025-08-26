#!/usr/bin/env bats
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

bats_require_minimum_version 1.5.0

## -----------------------------------------------------------------------
# This runs before each of the following tests are executed.
## -----------------------------------------------------------------------
setup() {
    source './utils/check_repo_tags.sh'

    ## Slurp a list of repositories to query
    readarray -t buffer < 'conf/repos/voltha'
    declare -g -a components=("${buffer[0]}")

    # Uncomment for raw test results
    # declare -g -i enable_fatal=1

    # Uncomment to skip remaining checks within a test.
    # declare -g -i enable_skip=1
}

## -----------------------------------------------------------------------
## Intent:
## -----------------------------------------------------------------------
@test 'Validate comp_deploy_tag()' {

    local component
    for component in "${components[@]}";
    do
        ## --------------
        ## Known problems
        ## --------------
        case "$component" in
            device-management-interface)
                if [[ -v enable_skip ]]; then
                    skip 'declare -a gerrit=([0]="1.15.0")'
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
            voltha-docs)
                if [[ -v enable_skip ]]; then
                    skip 'declare -a gerrit=([0]="2.12.35")'
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
            voltha-lib-go)
                if [[ -v enable_skip ]]; then
                    skip 'declare -a gerrit=([0]="7.5.3")'
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
            voltha-onos)
                if [[ -v enable_skip ]]; then
                    skip 'declare -a gerrit=([0]="2.11.0")'
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
            voltha-openolt-adapter)
                if [[ -v enable_skip ]]; then
                    skip 'declare -a gerrit=([0]="4.4.10")'
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
            voltha-openonu-adapter-go)
                if [[ -v enable_skip ]]; then
                    skip 'declare -a gerrit=([0]="2.12.0~beta")'
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
            voltha-protos)
                if [[ -v enable_skip ]]; then
                    skip 'declare -a gerrit=([0]="5.4.11")'
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
        esac

        # local component='voltha-openonu-adapter-go'
        declare -a gerrit=()
        gather_comp_deploy gerrit "http://gerrit.lfbroadband.org/${component}"

        declare -a github=()
        gather_comp_deploy gerrit "http://github.com/opencord/${component}"

        printf "\nCOMPONENT: %s\n" "$component" 1>&2
        declare -p gerrit 1>&2
        declare -p github 1>&2

        ## Check for deltas
        [[ "${gerrit[*]}" == "${github[@]}" ]]
    done

    ## -----------------------------------------
    ## Compare by size, filtered list is smaller
    ## -----------------------------------------
    [ true ]
}

## -----------------------------------------------------------------------
## Intent: Compare tags between gerrit and github repositories
## -----------------------------------------------------------------------
@test 'Validate comp_test_tag()' {

    local component
    for component in "${components[@]}";
    do
        ## --------------
        ## Known problems
        ## --------------
        case "$component" in
            device-management-interface)
                if [[ -v enable_skip ]]; then
                    skip 'declare -a gerrit=([0]="1.16.0")'
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
            voltha-onos)
                if [[ -v enable_skip ]]; then
                    skip "declare -a gerrit=([0]="2.12.0~beta")"
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
            voltha-lib-go)
                if [[ -v enable_skip ]]; then
                    skip "declare -a gerrit=([0]="7.6.0")"
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
            voltha-openolt-adapter)
                if [[ -v enable_skip ]]; then
                    skip "declare -a gerrit=([0]="4.4.11")"
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
            voltha-protos)
                if [[ -v enable_skip ]]; then
                    skip 'declare -a gerrit=([0]="5.5.0")'
                fi
                [[ ! -v enable_fatal ]] && { continue; }
                ;;
        esac

        # local component='voltha-openonu-adapter-go'
        declare -a gerrit=()
        gather_comp_test gerrit "http://gerrit.lfbroadband.org/${component}"

        declare -a github=()
        gather_comp_test gerrit "http://github.com/opencord/${component}"

        printf "\nCOMPONENT: %s\n" "$component" 1>&2
        declare -p gerrit 1>&2
        declare -p github 1>&2

        ## Check for deltas
        [[ "${gerrit[*]}" == "${github[@]}" ]]
    done
}

## -----------------------------------------------------------------------
## Intent:
## -----------------------------------------------------------------------
@test 'todo()' {

    cat <<EOM

1) Compare repository branches and tags:
   - Iterate over all repositories.
   - Retrieve branches and tags from gerrit.
   - Retrieve branches and tags from github.
   - Compare contents for discrepancies.
EOM

    [ true ]
}

# [EOF]
