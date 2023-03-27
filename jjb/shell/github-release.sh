#!/usr/bin/env bash
# -----------------------------------------------------------------------
# Copyright 2018-2023 Open Networking Foundation (ONF) and the ONF Contributors
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
declare -g __githost='github.com'

## -----------------------------------------------------------------------
## Uncomment to activate
## -----------------------------------------------------------------------
declare -i -g draft_release=1 
# declare -g TRACE=0  # uncomment to set -x

# shellcheck disable=SC2015
[[ -v TRACE ]] && { set -x; } || { set +x; } # SC2015 (shellcheck -x)

declare -a -g ARGV=()           # Capture args to minimize globals and arg passing
[[ $# -gt 0 ]] && ARGV=("$@")

declare -g scratch              # temp workspace for downloads
declare -g gh_cmd               # path to gh command

declare -g SCRIPT_VERSION='1.2' # git changeset needed

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

    do_logout
    return
}
trap sigtrap EXIT

## -----------------------------------------------------------------------
## Intent: Return a release version for queries
##   Note: Do not use in production, function is intended for interactive use
## -----------------------------------------------------------------------
function get_version()
{
    declare -n ref="$1"

    banner
    declare -a rev=()
    rev+=("$(( RANDOM % 10 + 1 ))")
    rev+=("$(( RANDOM % 256 + 1 ))")
    rev+=("$(( 6RANDOM % 10000 + 1 ))")
    ver="v${rev[0]}.${rev[1]}.${rev[2]}"

    func_echo "version: $ver"
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

    ## prime the stream
    local work
    get_release_dir work
    declare -p work
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
    local ver

    banner
    ver="$(git tag -l --points-at HEAD)"
    declare -p ver

    get_version 'ver'
    declare -p ver
    
    # ------------------------------------------------------
    # match bare versions or v-prefixed golang style version
    # Critical failure for new/yet-to-be-released repo ?
    # ------------------------------------------------------
    if [[ "$ver" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]
    then
	echo "git has a SemVer released version tag: '$ver'"
	echo "Building artifacts for GitHub release."
    else
	error "No SemVer released version tag found, exiting..."
    fi

    varname="$ver"
    func_echo "GIT_VERSION is [$GIT_VERSION] from $(/bin/pwd)"
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
# declare -g RELEASE_TEMP
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
    declare -n varname=$1; shift
    declare -g __repo_org
    
    local org
    if [[ -v __repo_org ]]; then
	org="$__repo_org"
    elif [[ ! -v GITHUB_ORGANIZATION ]]; then
	error "--repo-org or GITHUB_ORGANIZATION= are required"
    else
	org="${GITHUB_ORGANIZATION}"
    fi

    # shellcheck disable=SC2178
    varname="$org"
    return
}

## -----------------------------------------------------------------------
## Intent: Retrieve repository organizaiton name
## -----------------------------------------------------------------------
function get_gh_repo_name()
{
    declare -n varname=$1; shift
    declare -g __repo_name
    
    local name
    if [[ -v __repo_name ]]; then
	name="$__repo_name"
    elif [[ ! -v GITHUB_PROJECT ]]; then
	error "--repo-name or GITHUB_PROJECT= are required"
    else
	name="${GITHUB_PROJECT}"
    fi

    varname="$name"
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
    declare -n varname=$1; shift

    local repo_org
    get_gh_repo_org repo_org
    
    local repo_name
    get_gh_repo_name repo_name    
    
    varname="${repo_org}/${repo_name}"
    func_echo "VARNAME=$varname"
    return
}

## -----------------------------------------------------------------------
## Intent: Retrieve release name string "project - version"
## -----------------------------------------------------------------------
function get_argv_name()
{
    declare -n varname=$1; shift

    local repo_name
    get_gh_repo_name repo_name
    varname="${repo_name} - $GIT_VERSION"
    func_echo "varname=$varname"
    return
}

## -----------------------------------------------------------------------
## Intent: Retrieve tag version
## -----------------------------------------------------------------------
function get_argv_tag()
{
    declare -n varname=$1; shift

    # cached_argv_tag='v3.41.3204'
    if [[ ! -v cached_argv_tag ]]; then
	declare -g cached_argv_tag
	if [[ -v GIT_VERSION ]]; then # hello jenkins
	    cached_argv_tag="$GIT_VERSION"
	fi
    fi

    [[ ${#cached_argv_tag} -eq 0 ]] && error "Unable to determine GIT_VERSION="
    varname="$cached_argv_tag"
    func_echo "varname=$varname"
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
    declare -n varname=$1; shift

    DEST_GOPATH=${DEST_GOPATH:-}
    if [ -n "$DEST_GOPATH" ]; then
    # if [[ -v DEST_GOPATH ]] && [[ -n DEST_GOPATH ]]; then	
	## [jenkins] Suspect this will taint the golang installation.
	## [jenkins] Install succeeds, release fails, next job affected due to corruption.
	## [jenkins] Copy golang to temp then augment ?
	## [jenkins] Worst case (problematic) backup then restore
	mkdir -p "$GOPATH/src/$DEST_GOPATH"
	varname="$GOPATH/src/$DEST_GOPATH/$GERRIT_PROJECT"
	mv "$WORKSPACE/$GERRIT_PROJECT" "$varname"

    ## [TODO] - support local dev use
    # elif [[ ! -v WORKSPACE ]] && [[ -d '.git' ]]; then
	# project=$(git remote -v show | awk -F' ' '{print $2}' | xargs basename)
	# project=voltctl.git

    else
	varname="$WORKSPACE/$GERRIT_PROJECT"
    fi

    if [ ! -f "$varname/Makefile" ]; then
	:
    elif [ ! -f "$varname/makefile" ]; then
	:
    else
	error "Makefile not found at $varname!"
    fi

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
## Intent: Copy files from the build directory into the release staging
##         directory for publishing to github releases/ endpoint.
## -----------------------------------------------------------------------
function copyToRelease()
{
    func_echo "ENTER"

    local artifact_glob="${ARTIFACT_GLOB%/*}"
    func_echo "$(declare -p artifact_glob)"

    local work
    get_release_dir work

    ## Flatten filesystem, should we recurse here to release subdirs ?
    # cp $(which ls) "$work"
    # readarray -t arts < <(find "$artifact_glob" -type f)
    readarray -t arts < <(find "$work" -type f)
    [[ ${#arts[@]} -eq 0 ]] && error "Artifact dir is empty, check for build failures: $artifact_glob"
    
    # Copy artifacts into the release temp dir
    # shellcheck disable=SC2086
    echo "rsync -rv --checksum \"$artifact_glob/.\" \"$work/.\""
    rsync -rv --checksum "$artifact_glob/." "$work/."

    func_echo "LEAVE"

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

    local version="$GIT_VERSION"
#    get_version 'version'
#    create_release_by_version "$version"

    declare -a args=()
    args+=('--host-repo')
    args+=('--notes' "'Testing release create -- ignore'")
    args+=('--title')
    if [[ -v draft_release ]]; then
	args+=('--draft')
    else
	args+=('--discussion-category' 'Announcements')
    fi

    local work
    get_release_dir work

    # pushd "$work" >/dev/null
    readarray -t payload < <(find "$work" ! -type d -print)
    func_echo "$gh_cmd release create ${version} ${args[@]}" "${payload[@]}"
    my_gh 'release' 'create' "'$version'" "${args[@]}" "${payload[@]}"
    # popd >/dev/null

    return
}

## -----------------------------------------------------------------------
## Intent: Authenticate credentials for a github gh session
## -----------------------------------------------------------------------
## NOTE: my_gh currently unused due to --with-token < "$pac"
## -----------------------------------------------------------------------
function do_login()
{
    declare -g pac
    declare -a login_args=()
    [[ $# -gt 0 ]] && login_args+=("$@")

    func_echo "$(declare -p WORKSPACE)"

    # https://github.com/cli/cli/issues/2922#issuecomment-775027762
    # (sigh) why not quietly return VS forcing a special case
    # [[ -v WORKSPACE ]] && [[ -v GITHUB_TOKEN ]] && return

# 12:58:36 ** -----------------------------------------------------------------------
# 12:58:36 ** jenkins582353203049151829.sh::do_login: --hostname github.com
# 12:58:36 ** --------------------------------------------------------------------# ---
# 12:58:36 ** jenkins582353203049151829.sh :: do_login: Detected ENV{GITHUB_TOKEN}=
# 12:58:36 The value of the GITHUB_TOKEN environment variable is being used for authentication.
# 12:58:36 To have GitHub CLI store credentials instead, first clear the value from the environment.
    return
    
    # bridge to my_gh()
    get_gh_hostname login_args

    banner "${login_args[@]}"

    ## Read from disk is safer than export GITHUB_TOKEN=
    if [[ -v pac ]] && [[ ${#pac} -gt 0 ]]; then  # interactive/debugging
	[ ! -f "$pac" ] && error "PAC token file $pac does not exist"
	# func_echo "--token file is $pac"
        "$gh_cmd" auth login  "${login_args[@]}" --with-token < "$pac"

    elif [[ ! -v GITHUB_TOKEN ]]; then
        error "--token [t] or GITHUB_TOKEN= are required"

    else # jenkins
	func_echo 'Detected ENV{GITHUB_TOKEN}='
	"$gh_cmd" auth login  "${login_args[@]}"
    fi

    declare -i -g active_login=1 # signal logout

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

    declare -a out_args=()
    [[ $# -gt 0 ]] && out_args+=("$@")

    # bridge to my_gh()

    get_gh_hostname in_args

    banner "${out_args[@]}"
    "$gh_cmd" auth logout "${out_args[@]}" <<< 'Y'

    unset active_login
    return
}

## -----------------------------------------------------------------------
## Intent: Query for repository version strings
## -----------------------------------------------------------------------
function get_releases()
{
    declare -n ref="$1"; shift
    local func="${FUNCNAME[0]}"

    banner ""
    pushd "$scratch" >/dev/null
    
    # gh api repos/{owner}/{repo}/releases
    local releases_uri
    get_gh_releases releases_uri
    declare -p releases_uri
    
    ref=()
    "$gh_cmd" api "$releases_uri" "${common[@]}" | jq . > 'release.raw'
    readarray -t __tmp < <(jq '.[] | "\(.tag_name)"' 'release.raw')

    local release
    for release in "${__tmp[@]}";
    do
	release="${release//\"/}"
	ref+=("$release")
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
    declare -a releases=()
    get_releases releases

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

    readarray -t latest < <(curl --silent -qI "$latest" \
				| awk -F '/' '/^location/ {print  substr($NF, 1, length($NF)-1)}')
    declare -p latest
    if [ ${#latest[@]} -ne 1 ]; then
	error "Unable to determine latest gh package version"
    fi
       
    local VER="${latest[0]}"

    func_echo "Download latest gh binary"
    local url="https://github.com/cli/cli/releases/download/${VER}/gh_${VER#v}_linux_amd64.tar.gz"
    wget --quiet --output-document="$tarball" "$url"

    func_echo "Unpack tarball"
    tar zxf "$tarball"

    gh_cmd="$(find "${scratch}" -name 'gh' -print)"
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
    local version="$1"; shift
    
    banner "${in_args[@]}"
    declare -a args=()
    args+=('--host-repo')
    args+=('--yes')
    # args+=('--cleanup-tag')

#   ** github-release.sh :: get_argv_repo: VARNAME=opencord/voltctl
#* Running: /tmp/github-release.R7geZo7Ywo/gh_2.25.1_linux_amd64/bin/gh release delete v4.175.710 --repo 'github.com/opencord/voltctl' --yes --cleanup-tag
#rror connecting to 'github.com
#heck your internet connection or https://githubstatus.com

    echo
    echo "==========================================================================="
    my_gh 'release' 'delete' "$version" "${args[@]}"
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
    #  pushd "$RELEASE_TEMP"
    pushd "$release_temp" >/dev/null || error "pushd failed: dir is [$release_temp]"

    readarray -t to_release < <(find . -mindepth 1 -maxdepth 1 -type f -print)
    func_echo "Files to release: $(declare -p to_release)"

    # Generate and check checksums
    sha256sum -- * > checksum.SHA256
    sha256sum -c < checksum.SHA256

    echo
    func_echo "Checksums(checksum.SHA256):"
    cat checksum.SHA256

    ## ARGS NEEDED
    gh_release_create

    popd  >/dev/null || error "pushd failed: dir is [$release_temp]"

    return
}

## -----------------------------------------------------------------------
## Intent: Display program usage
## -----------------------------------------------------------------------
function usage()
{
    [[ $# -gt 0 ]] && func_echo "$*"

    cat <<EOH
Usage: github-release.sh [options] [target] ...

[Github CLI (gh) arguments]
  --login               Perform authentication using a PAC
  --logout
  --host [h]            Specify github server for connection.

[Options]
  --token               Login debugging, alternative to env var use.

[Modes]
  --debug               Enable script debug mode
  --verbose             Enable script verbose mode

All remaining arguments are passthrough to the gh command.
EOH

    exit 0
}

## -----------------------------------------------------------------------
## Intent: Provide common arguments and access to the gh command.
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
##   --verbose   Enable verbose mode#
##   --version   Display command version
##   @array      Remaining arguments passed as command switches.
## -----------------------------------------------------------------------
## See Also:
##   o https://cli.github.com/manual
## -----------------------------------------------------------------------
function my_gh()
{
    ## ------------------------
    ## Cache path to gh command
    ## ------------------------
    if [[ ! -v gh_cmd ]]; then
        readarray -t cmds < <(which -a gh)
        declare -p cmds
        if [ ${#cmds} -eq 0 ]; then
            error "Unable to locate the gh command"
        fi
        gh_cmd="${cmds[0]}"
        readonly gh_cmd
    fi

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
        case "$arg" in

	    # Modes
            -*debug)   declare -i -g debug=1 ;;
  	    -*help)    show_help             ;;
            -*verbose) args+=('--verbose')   ;;

	    -*hostname)
		get_gh_hostname in_args
                ;;

	    --host-repo)
		local val
		get_argv_repo val
		
		# --repo <[HOST/]OWNER/REPO>
		args+=('--repo' "'${__githost}/${val}'")
		;;
	    
	    --repo)
		local val
		get_argv_repo val
		args+=('--repo' "$val")
		;;

	    --tag)
		local val
       		get_argv_tag val
		args+=("'$val'")       # No switch, pass inline
		;;

	    --title)
		local val
		get_argv_name val
		args+=('--title' "'$val'")
		;;
	    
	    # --draft
	    # --latest
            *) args+=("$arg") ;;
        esac
    done
    
    cmd+=("${args[@]}")
    echo "** Running: ${cmd[*]}"
    "${cmd[@]}"
    local status=$?

    [[ $status -eq 0 ]] && { true; } || { false; }
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
  --pac                       Personal Access Token (file)

[DEBUG]
  --gen-version               Generate a random release version string.
  --git-hostname              Git server hostname (default=github.com)

EOH
    return
}

## ---------------------------------------------------------------------------
## Intent: Parse script command line arguments
## ---------------------------------------------------------------------------
function parse_args()
{
    # declare -n ref="$1"; shift

    [[ -v DEBUG ]] && func_echo "ENTER"

#    ref="repos/${__repo_user}/${__repo_name}/releases"
    
    while [ $# -gt 0 ]; do
	local arg="$1"; shift
	case "$arg" in
	    -*gen-version)
		get_version GIT_VERSION
		;;

	    -*git-hostname)
		__githost="$1"; shift
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
parse_args $@
init
install_gh_binary

do_login

release_path='/dev/null'
get_release_path release_path
declare -p release_path

pushd "$release_path" || error "pushd failed: dir is [$release_path]"

    # legacy: getGitVersion "$GERRIT_PROJECT" GIT_VERSION
    getGitVersion GIT_VERSION
    getReleaseDescription RELEASE_DESCRIPTION

    # build the release, can be multiple space separated targets
    # -----------------------------------------------------------------------
    # % go build command-line-arguments:
    #      copying /tmp/go-build4212845548/b001/exe/a.out:
    #      open release/voltctl-1.8.25-linux-amd64: permission denied
    #   missing: docker run mkdir
    # -----------------------------------------------------------------------
    # shellcheck disable=SC2086
    make "$RELEASE_TARGETS" || tyue

    copyToRelease

  cat <<EOM

** -----------------------------------------------------------------------
** Create the release:
**  1) Create initial github release with download area.
**  2) Generate checksum.SHA256 for all released files.
**  3) Upload files to complete the release.
**  4) Display released info from github.
** -----------------------------------------------------------------------
EOM

  showReleases
  # releaseDelete 'v4.175.710'
  release_staging
  popd  || error "pushd failed: dir is [$release_path]"
fi

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
