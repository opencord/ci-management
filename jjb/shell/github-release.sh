#!/usr/bin/env bash
# -----------------------------------------------------------------------
# Copyright 2018-2024 Open Networking Foundation (ONF) and the ONF Contributors
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
#
# github-release.sh
# builds (with make) and uploads release artifacts (binaries, etc.) to github
# given a tag also create checksums files and release notes from the commit
# message
# -----------------------------------------------------------------------

set -euo pipefail

##-------------------##
##---]  GLOBALS  [---##
##-------------------##
declare -g WORKSPACE
declare -g GERRIT_PROJECT
declare -g __githost=github.com
# export DEBUG=1

## -----------------------------------------------------------------------
## Uncomment to activate
## -----------------------------------------------------------------------
# Debug arguments
# declare -i -g argv_gen_version=1
# declare -i -g draft_release=1

declare -a -g ARGV=()           # Capture args to minimize globals and arg passing
[[ $# -gt 0 ]] && ARGV=("$@")

declare -g scratch              # temp workspace for downloads

declare -g SCRIPT_VERSION='1.4' # git changeset needed

##--------------------##
##---]  INCLUDES  [---##
##--------------------#

## -----------------------------------------------------------------------
## Intent: Register an interrupt handler to display a stack trace on error
## -----------------------------------------------------------------------
function errexit()
{
    local err=$?
    set +o xtrace
    local code="${1:-1}"

    local prefix="${BASH_SOURCE[1]}:${BASH_LINENO[0]}"
    echo -e "\nOFFENDER: ${prefix}"
    if [ $# -gt 0 ] && [ "$1" == '--stacktrace-quiet' ]; then
        code=1
    else
        echo "ERROR: '${BASH_COMMAND}' exited with status $err"
    fi

    # Print out the stack trace described by $function_stack
    if [ ${#FUNCNAME[@]} -gt 2 ]
    then
            echo "Call tree:"
            for ((i=1;i<${#FUNCNAME[@]}-1;i++))
            do
                echo " $i: ${BASH_SOURCE[$i+1]}:${BASH_LINENO[$i]} ${FUNCNAME[$i]}(...)"
            done
    fi

    echo "Exiting with status ${code}"
    echo
    exit "${code}"
    # return
}

# trap ERR to provide an error handler whenever a command exits nonzero
#  this is a more verbose version of set -o errexit
trap errexit ERR

# setting errtrace allows our ERR trap handler to be propagated to functions,
#  expansions and subshells
set -o errtrace

## -----------------------------------------------------------------------
## Intent: Cleanup scratch area on exit
## -----------------------------------------------------------------------
function sigtrap()
{
    ## Prevent mishaps
    local is_read_only
    is_read_only="$(declare -p scratch)"
    if [[ $is_read_only != *"declare -r scratch="* ]]; then
        echo "ERROR: variable scratch is not read-only, cleanup skipped"
        exit 1
    fi

    if [ -d "$scratch" ]; then
        /bin/rm -fr "$scratch"
    fi

    do_logout 'shellcheck-SC2119'
    return
}
trap sigtrap EXIT

## -----------------------------------------------------------------------
## Intent: Return a random release version string.
## -----------------------------------------------------------------------
##   Note: Do not use this function in production.  get_version() is
##         intended for local use or debugging $0 from within a jenkins
##         job.
## -----------------------------------------------------------------------
function get_version()
{
    declare -n ref="$1"

    declare -a rev=()
    rev+=("$(( RANDOM % 10 + 1 ))")
    rev+=("$(( RANDOM % 256 + 1 ))")
    rev+=("$(( RANDOM % 10000 + 1 ))")
    local ver="v${rev[0]}.${rev[1]}.${rev[2]}"

    func_echo "VERSION: $ver"
    ref="$ver"
    return
}

## -----------------------------------------------------------------------
## Intent: Provide defaults for environment variables
## -----------------------------------------------------------------------
function initEnvVars()
{
    # when not running under Jenkins, use current dir as workspace and a blank
    # project name
    declare -g WORKSPACE=${WORKSPACE:-.}
    declare -g GERRIT_PROJECT=${GERRIT_PROJECT:-}

    # Github organization (or user) this project is published on.  Project name should
    # be the same on both Gerrit and GitHub
    declare -g GITHUB_ORGANIZATION=${GITHUB_ORGANIZATION:-}

    # glob pattern relative to project dir matching release artifacts
    # ARTIFACT_GLOB=${ARTIFACT_GLOB:-"release/*"} # stat -- release/* not found, literal string (?)
    declare -g ARTIFACT_GLOB=${ARTIFACT_GLOB:-"release/."}

    # Use "release" as the default makefile target, can be a space separated list
    declare -g RELEASE_TARGETS=${RELEASE_TARGETS:-release}

    # Set and handle GOPATH and PATH
    export GOPATH=${GOPATH:-$WORKSPACE/go}
    export PATH=$PATH:/usr/lib/go-1.12/bin:/usr/local/go/bin:$GOPATH/bin
    return
}

## -----------------------------------------------------------------------
## Intent: Create a scratch area for downloads and transient tools
##         temp directory will be automatically removed upon exit.
## -----------------------------------------------------------------------
function init()
{
    local pkgbase="${0##*/}" # basename
    local pkgname="${pkgbase%.*}"

    # initEnvVars # moved to full_banner()

    ## Create a temp directory for auto-cleanup
    declare -g scratch
    scratch="$(mktemp -d -t "${pkgname}.XXXXXXXXXX")"
    readonly scratch
    declare -p scratch

    ## prime the stream: cache answers
    local work
    get_release_dir work
    declare -p work

    local git_version
    getGitVersion git_version
    func_echo "$(declare -p git_version)"
    return
}

## -----------------------------------------------------------------------
## Intent: Verbose output for logging
## -----------------------------------------------------------------------
function banner()
{
    local iam="${0##*/}"
    cat <<EOB

** -----------------------------------------------------------------------
** ${iam}::${FUNCNAME[1]}: $*
** -----------------------------------------------------------------------
EOB
}

## -----------------------------------------------------------------------
## Intent: Output a log banner to identify the running script/version.
## -----------------------------------------------------------------------
## TODO:
##   o SCRIPT_VERSION => git changeset for repo:ci-managment
##   o echo "library version: ${env."library.libName.version"}"
# -----------------------------------------------------------------------
# 14:18:38   > git fetch --no-tags --progress -- https://gerrit.opencord.org/ci-management.git +refs/heads/*:refs/remotes/origin/* # timeout=10
# 14:18:39  Checking out Revision 50f6e0b97f449b32d32ec0e02d59642000351847 (master)
# -----------------------------------------------------------------------
function full_banner()
{
    local iam="${0##*/}"

    initEnvVars   # set defaults

cat <<EOH

** -----------------------------------------------------------------------
** IAM: ${iam} :: ${FUNCNAME[0]}
** ARGV: ${ARGV[@]}
** PWD: $(/bin/pwd)
** NOW: $(date '+%Y/%m/%d %H:%M:%S')
** VER: ${SCRIPT_VERSION:-'unknown'}
** -----------------------------------------------------------------------
**      GERRIT_PROJECT: $(declare -p GERRIT_PROJECT)
** GITHUB_ORGANIZATION: $(declare -p GITHUB_ORGANIZATION)
**     RELEASE_TARGETS: $(declare -p RELEASE_TARGETS)
**              GOPATH: $(declare -p GOPATH)
** -----------------------------------------------------------------------
** PATH += /usr/lib/go-1.12/bin:/usr/local/go/bin:GOPATH/bin
** -----------------------------------------------------------------------
EOH

    return
}

## -----------------------------------------------------------------------
## Intent: Display a message with 'iam' identifier for logging
## -----------------------------------------------------------------------
function func_echo()
{
    local iam="${0##*/}"
    echo "** ${iam} :: ${FUNCNAME[1]}: $*"
    return
}

## -----------------------------------------------------------------------
## Intent: Display an error message then exit with status.
## -----------------------------------------------------------------------
function error()
{
    local iam="${0##*/}"
    echo "ERROR ${iam} :: ${FUNCNAME[1]}: $*"
    exit 1
}

## -----------------------------------------------------------------------
## Intent: Verify sandbox/build is versioned for release.
## -----------------------------------------------------------------------
function getGitVersion()
{
    declare -n varname="$1"; shift

    local __ver # use prefix('__') to protect callers variable name
    if [[ -v cached_getGitVersion ]]; then
        __ver="$cached_getGitVersion"
        varname="$__ver"
        return

    elif [[ -v argv_gen_version ]]; then
        get_version __ver

    elif [[ -v WORKSPACE ]] && [[ -v GITHUB_TOKEN ]]; then # i_am_jenkins
        local path="$GERRIT_PROJECT"
        pushd "$path" || error "pushd GERRIT_PROJECT= failed $(declare -p path)"
        __ver="$(git tag -l --points-at HEAD)"
        popd || error "popd GERRIT_PROJECT= failed"

    elif [[ -v argv_version_file ]]; then # local debug
        [[ ! -f VERSION ]] && error "./VERSION file not found"
        readarray -t tmp < <(sed -e 's/[[:blank:]]//g' 'VERSION')
        __ver="v${tmp[0]}"

    else
        cd ..
        local path="$GERRIT_PROJECT"
        pushd "$path" || error "pushd GERRIT_PROJECT= failed $(declare -p path)"
        __ver="$(git tag -l --points-at HEAD)"
        popd || error "popd GERRIT_PROJECT= failed"
    fi

    # ------------------------------------------------------
    # match bare versions or v-prefixed golang style version
    # Critical failure for new/yet-to-be-released repo ?
    # ------------------------------------------------------
    if [[ "$__ver" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
        echo "git has a SemVer released version tag: '$__ver'"
        echo "Building artifacts for GitHub release."

    elif [[ "$__ver" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)-dev([0-9]+)$ ]]; then
	# v1.2.3-dev (*-dev*) is an implicit draft release.
        declare -i -g draft_release=1
        echo "Detected --draft SemVer release version tag [$__ver]"
        echo "Building artifacts for GitHub release."

    # Should also accept:  X.Y.Z-{alpha,beta,...}

    else
        echo "Version string contains: [${__ver}]"
        error "No SemVer released version tag found, exiting..."
    fi

    ## Cache value for subsequent calls.
    ## readonly is a guarantee assignment happens once.
    declare -g cached_getGitVersion="$__ver"
    readonly cached_getGitVersion;

    varname="$__ver"
    func_echo "Version is [$__ver] from $(/bin/pwd)"
    return
}

## -----------------------------------------------------------------------
## Intent:
##   Note: Release description is sanitized version of the log message
## -----------------------------------------------------------------------
function getReleaseDescription()
{
    declare -n varname="$1"; shift

    local msg
    msg="$(git log -1 --pretty=%B)"

    local val
    val="$(tr -dc "[:alnum:]\n\r\.\[\]\:\-\\\/\`\' " <<< "$msg")"

    [[ ${#val} -eq  0 ]] && error "Release Description is empty ($msg)"
    varname="$val"
    return
}

## -----------------------------------------------------------------------
## Intent: Retrieve value of the release temp directory.
## -----------------------------------------------------------------------
## Note: Limit use of globals so logic can be isolated in function calls.
## -----------------------------------------------------------------------
function get_release_dir()
{
    declare -n varname=$1; shift

    # Temporary staging directory to copy artifacts to
    varname="$scratch/release"
    mkdir -p "$varname"
    return
}

## -----------------------------------------------------------------------
## Intent: Retrieve github server hostname
## -----------------------------------------------------------------------
function get_gh_hostname()
{
    declare -n varname=$1; shift
    varname+=('--hostname' "${__githost}")
    return
}

## -----------------------------------------------------------------------
## Intent: Retrieve repository organizaiton name
## -----------------------------------------------------------------------
function get_gh_repo_org()
{
    declare -n ref=$1; shift
    declare -g __repo_org

    local org
    if [[ -v __repo_org ]]; then
        org="$__repo_org"
    elif [[ ! -v GITHUB_ORGANIZATION ]]; then
        error "--repo-org or GITHUB_ORGANIZATION= are required"
    else
        org="${GITHUB_ORGANIZATION}"
    fi

    ref="$org"
    return
}

## -----------------------------------------------------------------------
## Intent: Retrieve repository organizaiton name
## -----------------------------------------------------------------------
function get_gh_repo_name()
{
    declare -n ref=$1; shift
    declare -g __repo_name

    local name
    if [[ -v __repo_name ]]; then
        name="$__repo_name"
    elif [[ ! -v GERRIT_PROJECT ]]; then
        error "--repo-name or GERRIT_PROJECT= are required"
    else
        name="${GERRIT_PROJECT}"
    fi

    ref="$name"
    return
}

## -----------------------------------------------------------------------
## Intent: Return path for the gh release query
## -----------------------------------------------------------------------
function get_gh_releases()
{
    declare -n ref="$1"

    local repo_org
    get_gh_repo_org repo_org

    local repo_name
    get_gh_repo_name repo_name

    ref="repos/${repo_org}/${repo_name}/releases"
    func_echo "ref=$ref"
    return
}

## -----------------------------------------------------------------------
## Intent: Retrieve repository path argument
## -----------------------------------------------------------------------
function get_argv_repo()
{
    declare -n ref=$1; shift

    local repo_org
    get_gh_repo_org repo_org

    local repo_name
    get_gh_repo_name repo_name

    ref="${repo_org}/${repo_name}"
    # func_echo "ref=$(declare -p ref)"
    return
}

## -----------------------------------------------------------------------
## Intent: Retrieve release name string "project - version"
## -----------------------------------------------------------------------
function get_argv_name()
{
    declare -n ref=$1; shift

    local repo_name
    get_gh_repo_name repo_name

    local repo_ver
    getGitVersion repo_ver

    # ref="${repo_name} - $GIT_VERSION"
    ref="${repo_name} - ${repo_ver}"
    func_echo "ref=$(declare -p ref)"
    return
}

## -----------------------------------------------------------------------
## Intent: Retrieve tag version
## -----------------------------------------------------------------------
function get_argv_tag()
{
    declare -n ref=$1; shift

    # cached_argv_tag='v3.41.3204'
    if [[ ! -v cached_argv_tag ]]; then
        declare -g cached_argv_tag
        if [[ -v GIT_VERSION ]]; then # hello jenkins
            cached_argv_tag="$GIT_VERSION"
        fi
    fi

    [[ ${#cached_argv_tag} -eq 0 ]] && error "Unable to determine GIT_VERSION="
    ref="$cached_argv_tag"
    func_echo "ref=$(declare -p ref)"
    return
}

## -----------------------------------------------------------------------
## Intent:
## -----------------------------------------------------------------------
# To support golang projects that require GOPATH to be set and code checked out there
# If $DEST_GOPATH is not an empty string:
# - create GOPATH within WORKSPACE, and destination directory within
# - set PATH to include $GOPATH/bin and the system go binaries
# - move project from $WORKSPACE/$GERRIT_PROJECT to new location in $GOPATH
# - start release process within that directory
## -----------------------------------------------------------------------
function get_release_path()
{
    declare -n ref=$1; shift

    # shellcheck disable=SC2128
    local varpath="$ref"
    
    DEST_GOPATH=${DEST_GOPATH:-}
    if [ -n "$DEST_GOPATH" ]; then
        mkdir -p "$GOPATH/src/$DEST_GOPATH"
        varpath="$GOPATH/src/$DEST_GOPATH/$GERRIT_PROJECT"
        mv "$WORKSPACE/$GERRIT_PROJECT" "$varpath"
    else
        varpath="$WORKSPACE/$GERRIT_PROJECT"
    fi

    ## Verify pwd is OK
    for path in \
        "${varpath}/Makefile"\
        "${varpath}/makefile"\
        "__ERROR__"\
    ; do
        case "$path" in
            __ERROR__) error "Makefile not found at ${varpath} !" ;;
            *) [[ -f "$path" ]] && break ;;
        esac
    done

    return
}

## -----------------------------------------------------------------------
## Intent: Display future enhancements
## -----------------------------------------------------------------------
function todo()
{
    local iam="${0##*/}"

cat <<EOT

** -----------------------------------------------------------------------
** IAM: ${iam} :: ${FUNCNAME[0]}
** -----------------------------------------------------------------------
  o get_release_path()
      - refactor redundant paths into local vars.
      - see comments, do we have a one-off failure condition ?
  o PATH += golang appended 3 times, release needs a single, reliable answer.
  o do_login, do_logout and api calls do not use the my_gh wrapper:
      - Add a lookup function to cache and retrieve path to downloaded gh command.

EOT

    return
}

## -----------------------------------------------------------------------
## Intent: Verify a directory contains content for release.
## -----------------------------------------------------------------------
## Given:
##   scalar   Path to release/ directory
##   ref      Results returned through this indirect var.
## -----------------------------------------------------------------------
## Usage:
##   declare -a artifacts=()
##   get_artifacts '/foo/bar/tans' artifacts
##   declare -p artifacts
## -----------------------------------------------------------------------
function get_artifacts()
{
    local dir="$1"    ; shift
    declare -n refA=$1 ; shift

    # Glob available files, exclude checksums
    readarray -t __artifacts < <(find "$dir" -mindepth 1 ! -type d \
                                | grep -iv -e 'sum256' -e 'checksum')
    # func_echo "$(declare -p __artifacts)"

    # -----------------------------------------------------------------------
    # Verify count>0 to inhibit source-only release
    # Problem children:
    #   o build or make release failures.
    #   o docker container filesystem mount problem (~volume)
    # -----------------------------------------------------------------------
    [[ ${#__artifacts[@]} -eq 0 ]] \
          && error "Artifact dir is empty: $(declare -p dir)"

    # shellcheck disable=SC2034
    refA=("${__artifacts[@]}")
    return
}

## -----------------------------------------------------------------------
## Intent: Copy files from the build directory into the release staging
##         directory for publishing to github releases/ endpoint.
## -----------------------------------------------------------------------
function copyToRelease()
{
    banner ''

    local artifact_glob="${ARTIFACT_GLOB%/*}"
    func_echo "$(declare -p artifact_glob)"

    local work
    get_release_dir work
    func_echo "Artifact dir: $(declare -p work)"

    ## Verify release content is available
    declare -a artifacts=()
    # get_artifacts "$work" artifacts
    get_artifacts "$artifact_glob" artifacts
    func_echo "Artifacts in copy_from: $(declare -p artifacts)"

    # Copy artifacts into the release temp dir
    # shellcheck disable=SC2086
    echo "rsync -rv --checksum \"$artifact_glob/.\" \"$work/.\""
    rsync -rv --checksum "$artifact_glob/." "$work/."

    get_artifacts "$work" artifacts
    func_echo "Artifacts in copy_to: $(declare -p artifacts)"

    return
}

## -----------------------------------------------------------------------
## https://docs.github.com/en/rest/releases?apiVersion=2022-11-28
    # https://cli.github.com/manual/gh_release_create
    # --target <branch> or commit SHA
    # --title
    # --generate-notes
    # --release-notes (notes file)
    # --release
    # release create dist/*.tgz
    # --discussion-category "General"
## -----------------------------------------------------------------------
# https://cli.github.com/manual/gh_release_create
## -----------------------------------------------------------------------
function gh_release_create()
{
    banner ''

    local version
    getGitVersion version

    local work
    get_release_dir work

    declare -a args=()
    args+=('--host-repo')
    args+=('--title')
    if [[ -v draft_release ]]; then
        args+=('--draft')
    else
        args+=('--discussion-category' 'Announcements')
    fi

    if [[ -v release_notes ]]; then
        args+=('--notes-file' "$release_notes")
    fi

    pushd "$work/.." >/dev/null
    # func_echo "WORK=$work"
    readarray -t payload < <(find 'release' -maxdepth 4 ! -type d -print)

    func_echo "$gh_cmd release create ${version} ${args[*]} ${payload[*]}"

    if [[ -v dry_run ]]; then
        echo "[SKIP] dry run"
    else
        func_echo "my_gh release create '$version' ${args[*]} ${payload[*]}"
        my_gh 'release' 'create' "$version" "${args[@]}" "${payload[@]}"
    fi
    popd >/dev/null

    return
}

## -----------------------------------------------------------------------
## Intent: Authenticate credentials for a github gh session
## -----------------------------------------------------------------------
## NOTE: my_gh currently unused due to --with-token < "$pac"
## -----------------------------------------------------------------------
function do_login()
{
    # shellcheck disable=SC2120
    # shellcheck disable=SC2034
    local unused="$1"; shift

    declare -g pac
    declare -a login_args=()
    [[ $# -gt 0 ]] && login_args+=("$@")

    # https://github.com/cli/cli/issues/2922#issuecomment-775027762
    # (sigh) why not quietly return VS forcing a special logic path
    # [[ -v WORKSPACE ]] && [[ -v GITHUB_TOKEN ]] && return

# 12:58:36 ** -----------------------------------------------------------------------
# 12:58:36 ** jenkins582353203049151829.sh::do_login: --hostname github.com
# 12:58:36 ** --------------------------------------------------------------------# ---
# 12:58:36 ** jenkins582353203049151829.sh :: do_login: Detected ENV{GITHUB_TOKEN}=
# 12:58:36 The value of the GITHUB_TOKEN environment variable is being used for authentication.
# 12:58:36 To have GitHub CLI store credentials instead, first clear the value from the environment.
# -----------------------------------------------------------------------

    get_gh_hostname login_args

    banner "${login_args[@]}"
    func_echo "$(declare -p WORKSPACE)"

    ## Read from disk is safer than export GITHUB_TOKEN=
    if [[ -v pac ]] && [[ ${#pac} -gt 0 ]]; then  # interactive/debugging
        [ ! -f "$pac" ] && error "PAC token file $pac does not exist"
        func_echo "$gh_cmd auth login ${login_args[*]} --with-token < $pac"
        "$gh_cmd" auth login  "${login_args[@]}" --with-token < "$pac"

    elif [[ ! -v GITHUB_TOKEN ]]; then
        error "--token [t] or GITHUB_TOKEN= are required"

    else # jenkins
        func_echo "$gh_cmd auth login ${login_args[*]} (ie: jenkins)"

        # https://github.com/cli/cli/issues/2922#issuecomment-775027762
        # When using GITHUB_TOKEN, there is no need to even run gh auth login
        # "$gh_cmd" auth login  "${login_args[@]}"
    fi

    declare -i -g active_login=1 # signal logout needed

    return
}

## -----------------------------------------------------------------------
## Intent: Destroy credentials/gh session authenticated by do_login
## -----------------------------------------------------------------------
## NOTE: my_gh currently unused due to "<<< 'y'"
## -----------------------------------------------------------------------
function do_logout()
{
    declare -i -g active_login
    [[ ! -v active_login ]] && return

    declare -a logout_args=()
    [[ $# -gt 0 ]] && logout_args+=("$@")

    get_gh_hostname logout_args

    banner "${logout_args[@]}"
    func_echo "$gh_cmd auth logout ${logout_args[*]} <<< 'Y'"
    "$gh_cmd" auth logout "${logout_args[@]}" <<< 'Y'

    unset active_login
    return
}

## -----------------------------------------------------------------------
## Intent: Query for repository version strings
## -----------------------------------------------------------------------
function get_releases()
{
    declare -n refA="$1"; shift

    banner ""
    pushd "$scratch" >/dev/null

    # gh api repos/{owner}/{repo}/releases
    local releases_uri
    get_gh_releases releases_uri
    # declare -p releases_uri

    refA=()
    "$gh_cmd" api "$releases_uri" | jq . > 'release.raw'
    readarray -t __tmp < <(jq '.[] | "\(.tag_name)"' 'release.raw')

    local release
    for release in "${__tmp[@]}";
    do
        release="${release//\"/}"
        refA+=("$release")
    done

    popd >/dev/null
    return
}

## -----------------------------------------------------------------------
## Intent: Display repository query strings.
## Indirect: verify authentication and API
## -----------------------------------------------------------------------
function showReleases()
{
    declare -a raw=()
    get_releases raw

    ## Sort for display, we may need to prune volume later on
    readarray -t releases < <(sort -nr <<<"${raw[*]}")
    # IFS=$'\n' releases=($(sort -nr <<<"${raw[*]}"))
    # unset IFS

    local release
    for release in "${releases[@]}";
    do
        func_echo "$release"
    done
    return
}

## -----------------------------------------------------------------------
## Intent: Install the gh command line tool locally
## -----------------------------------------------------------------------
function install_gh_binary()
{
    banner

    pushd "$scratch"
    func_echo "Retrieve latest gh download URLs"

    local latest="https://github.com/cli/cli/releases/latest"
    local tarball="gh.tar.tgz"

    readarray -t latest < <(\
        curl --silent -qI "$latest" \
        | awk -F '/' '/^location/ {print  substr($NF, 1, length($NF)-1)}')
    declare -p latest
    if [ ${#latest[@]} -ne 1 ]; then
        error "Unable to determine latest gh package version"
    fi

    local VER="${latest[0]}"

    func_echo "Download latest gh binary"
    local url="https://github.com/cli/cli/releases/download/${VER}/gh_${VER#v}_linux_amd64.tar.gz"
    func_echo "wget --quiet --output-document='$tarball' '$url'"
    wget --quiet --output-document="$tarball" "$url"

    func_echo "Unpack tarball"
    tar zxf "$tarball"

    declare -g gh_cmd
    gh_cmd="$(find "${scratch}" -name 'gh' -print)"
    #declare -g gh_cmd='/usr/bin/gh'
    readonly gh_cmd

    func_echo "Command: ${gh_cmd}"
    func_echo "Version: $("$gh_cmd" --version)"
    popd

    return
}

## -----------------------------------------------------------------------
## Intent: Danger Will Robinson
## -----------------------------------------------------------------------
function releaseDelete()
{
    declare -n refA=$1; shift
    local version="$1"; shift

    banner "${refA[@]}"
    # declare -a refA=()
    refA+=('--host-repo')
    refA+=('--yes')
    # refA+=('--cleanup-tag')

    echo
    echo "==========================================================================="
    my_gh 'release' 'delete' "$version" "${refA[@]}"
    echo "==========================================================================="
    echo

    showReleases
    return
}

## -----------------------------------------------------------------------
## Intent: Copy binaries into temp/release, checksum then publish
## -----------------------------------------------------------------------
function release_staging()
{
    local release_temp
    get_release_dir release_temp

    banner ''
    func_echo "Packaging release files"

    pushd "$release_temp" >/dev/null \
        || error "pushd failed: dir is [$release_temp]"

    declare -a to_release=()
    get_artifacts '.' to_release

    if false; then
        for fyl in "${to_release[@]}";
        do
            func_echo "sha256sum $fyl > ${fyl}.sha256"
            sha256sum "$fyl" > "${fyl}.sha256"
        done
    fi

    # Generate and check checksums
    sha256sum -- * | grep -iv -e 'checksum' -e 'sha256' > checksum.SHA256
    sha256sum -c < checksum.SHA256

    echo
    func_echo "Checksums(checksum.SHA256):"
    cat checksum.SHA256

    if false; then
        # Careful with credentials display
        get_gh_hostname login_args
        banner "gh auth status ${login_args[*]}"
        gh auth status "${login_args[@]}"
    fi

    gh_release_create # publish

    popd  >/dev/null || error "pushd failed: dir is [$release_temp]"

    return
}

## -----------------------------------------------------------------------
## Intent: Normalize common arguments and access to the gh command.
##   o Cache path to the gh command
##   o Construct a gh command line from given args
##   o Command wrapper can provide defaults (--hostname github.com)
## -----------------------------------------------------------------------
## Given:
##   scalar      Array variable name (declare -n is a reference)
## Return:
##   ref         gh command line passed back to caller
## Switches:
##   --host      Pass default github hostname
##   --verbose   Enable verbose mode
##   --version   Display command version
##   @array      Remaining arguments passed as command switches.
## -----------------------------------------------------------------------
## See Also:
##   o https://cli.github.com/manual
## -----------------------------------------------------------------------
function my_gh()
{
    func_echo "ENTER"    
    set -x
 
    declare -a cmd=()
    cmd+=("$gh_cmd")

    ## ----------------------
    ## Construct command line
    ## ----------------------
    # shellcheck disable=SC2034
    declare -A action=()       # pending operations
    declare -a args=()         # common gh command line args

    while [ $# -gt 0 ]; do
        local arg="$1"; shift
	func_echo "function arg is [$arg]"
        case "$arg" in

            # Modes
            -*debug)	
		# shellcheck disable=SC2034
		declare -i -g debug=1
		;;
            -*verbose) args+=('--verbose') ;;

            -*hostname)
                get_gh_hostname in_args
                ;;

            --host-repo)
                local val
                get_argv_repo val

                # --repo <[HOST/]OWNER/REPO>
                args+=('--repo' "${__githost}/${val}")
                ;;

# args+=('--repo' 'github.com/opencord/bbsim')

            --repo)
                local val
                get_argv_repo val
                args+=('--repo' "$val")
                ;;

            --tag)
                local val
                       get_argv_tag val
                args+=("$val")       # No switch, pass inline
                ;;

            --title)
                local val
                get_argv_name val
                args+=('--title' "'$val'")
                ;;

            *) args+=("$arg") ;;
        esac
    done

    cmd+=("${args[@]}")

    echo
    declare -p cmd

    echo
    echo "** Running: ${cmd[*]}"
    "${cmd[@]}"
    local status=$?

    set +x
    func_echo "LEAVE"

    if [[ $status -eq 0 ]]; then
	true
    else
	false
    fi

    return
}

## ---------------------------------------------------------------------------
## Intent:
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
  --version-file              Read version string from local version file (vs env var)

[MODES]
  --debug                     Enable script debug mode
  --draft                     Create a draft release (vs published)
  --dry-run                   Simulation mode
  --todo                      Display future enhancement list

All other arguments are pass-through to the gh command.

Usage: $0 --draft --repo-org opencord --repo-name voltctl --git-hostname github.com --pac ~/access.pac

EOH

    return
}

## ---------------------------------------------------------------------------
## Intent: Parse script command line arguments
## ---------------------------------------------------------------------------
function parse_args()
{
    [[ -v DEBUG ]] && func_echo "ENTER"

    while [ $# -gt 0 ]; do
        local arg="$1"; shift
        func_echo "ARGV: $arg"

	# shellcheck disable=SC2034
        case "$arg" in

            -*debug)   declare -i -g debug=1         ;;
            --draft)   declare -i -g draft_release=1 ;;
            --dry-run) declare -i -g dry_run=1       ;;

            --version-file)
                declare -i -g argv_version_file=1
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

            -*help)
                usage
                exit 0
                ;;

            *) error "Detected unknown argument $arg" ;;
        esac
    done

    return
}

##----------------##
##---]  MAIN  [---##
##----------------##
iam="${0##*/}"

full_banner
parse_args "$@"
init
install_gh_binary

do_login "$*"

release_path='/dev/null'
get_release_path release_path
declare -p release_path

pushd "$release_path" || error "pushd failed: dir is [$release_path]"

    # legacy: getGitVersion "$GERRIT_PROJECT" GIT_VERSION
    getGitVersion GIT_VERSION
    getReleaseDescription RELEASE_DESCRIPTION
    if [[ ! -v release_notes ]]; then
	func_echo "Generating release notes from RELEASE_DESCRIPTION"
        declare -g release_notes="$scratch/release.notes"
        echo "$RELEASE_DESCRIPTION" > "$release_notes"
    fi
    cat "$release_notes"

    cat <<EOM

** -----------------------------------------------------------------------
**         GIT VERSION: $(declare -p GIT_VERSION)
** RELEASE_DESCRIPTION: $(declare -p RELEASE_DESCRIPTION)
**     RELEASE_TARGETS: $(declare -p RELEASE_TARGETS)
** -----------------------------------------------------------------------
** URL: https://github.com/opencord/bbsim/releases
** -----------------------------------------------------------------------
** Running: make ${RELEASE_TARGETS}
** -----------------------------------------------------------------------
EOM
    # build the release, can be multiple space separated targets
    # -----------------------------------------------------------------------
    # % go build command-line-arguments:
    #      copying /tmp/go-build4212845548/b001/exe/a.out:
    #      open release/voltctl-1.8.25-linux-amd64: permission denied
    #   missing: docker run mkdir
    # -----------------------------------------------------------------------
    # shellcheck disable=SC2086
    make "$RELEASE_TARGETS"
    copyToRelease

  cat <<EOM

** -----------------------------------------------------------------------
** Create the release:
**  1) Create initial github release with download area.
**  2) Generate checksum.SHA256 for all released files.
**  3) Upload files to complete the release.
**  4) Display released info from github
** -----------------------------------------------------------------------
EOM

  showReleases
  release_staging

  # Useful to display but --draft images use a non-standard subdir identifier.
  # showReleaseUrl

  popd  || error "pushd failed: dir is [$release_path]"

do_logout

# [SEE ALSO]
# -----------------------------------------------------------------------
# https://www.shellcheck.net/wiki/SC2236
# https://docs.github.com/en/rest/releases/releases?apiVersion=2022-11-28#create-a-release
# -----------------------------------------------------------------------
# https://cli.github.com/manual/gh_help_reference
# https://cli.github.com/manual/gh_release
# -----------------------------------------------------------------------

# [EOF]
