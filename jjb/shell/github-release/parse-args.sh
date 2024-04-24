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
## Intent: Parse script command line arguments
## ---------------------------------------------------------------------------
function parse_args()
{
    while [ $# -gt 0 ]; do
        local arg="$1"; shift
        func_echo "ARGV: $arg"

        # shellcheck disable=SC2034
        case "$arg" in

            -*debug)   declare -i -g debug=1         ;;
            --draft)   declare -i -g draft_release=1 ;;
            --dry-run) declare -i -g dry_run=1       ;;

            --version-file)
                # [TODO] pass arg and infer path from that
                declare -i -g argv_version_file=1

                if [[ $# -gt 0 ]] && [[ "$1" == *"/VERSION" ]]; then
                    arg="$1"; shift
                    [[ ! -e "$arg" ]] && { error "--version-file $arg does not exist"; }
                    local path="${arg%/*}"
                    cd "$path" || { error "cd $path: directory does not exist"; }
                fi
                ;;

            -*gen-version)
                declare -g -i argv_gen_version=1
                ;;

            -*git-hostname)
                __githost="$1"; shift
                ;;

            -*release-notes)
                [[ ! -f "$1" ]] && error "--release-notes: file path required (arg=\"$arg\")"
                declare -g release_notes="$1"; shift
                ;;

            -*repo-name)
                __repo_name="$1"; shift
                ;;

            -*repo-org)
                __repo_org="$1"; shift
                ;;

            -*pac)
                declare -g pac="$1"; shift
                readonly pac
                [[ ! -f "$pac" ]] && error "--token= does not exist ($pac)"
                : # nop/true
                ;;

            -*todo) todo ;;

            --self-check) declare -g -i argv_self_check=1 ;;

            -*help) usage; exit 0 ;;

            *) error "Detected unknown argument $arg" ;;
        esac
    done

    return
}

: # assign $?=0 for source $script

# [EOF]
