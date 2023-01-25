# -*- sh -*-
## -----------------------------------------------------------------------
## Intent:
##   o This script can be used as a one-liner for sourcing common scripts.
##   o Preserve command line arguments passed.
##   o Accept common.sh arguments specifying a set of libraries to load.
##   o Dependent common libraries will automatically be sourced.
## -----------------------------------------------------------------------
## Usage:
##   o source common.sh --common-args-begin-- --tempdir
##   o source common.sh --common-args-begin-- --stacktrace
## -----------------------------------------------------------------------

# __DEBUG_COMMON__=1
[[ -v __DEBUG_COMMON__ ]] && echo " ** ${BASH_SOURCE[0]}: BEGIN"

## -----------------------------------------------------------------------
## Intent: Anonymous function used to source common shell libs
## -----------------------------------------------------------------------
## Usage: source common.sh '--stacktrace'
## -----------------------------------------------------------------------
function __anon_func__()
{
    local iam="${BASH_SOURCE[0]%/*}"
    local cab='--common-args-begin--'

    declare -a args=($*)

    local raw
    raw="$(readlink --canonicalize-existing --no-newline "${BASH_SOURCE[0]}")"
    local top="${raw%/*}"
    local common="${top}/common/sh"

    local arg
    for arg in "${args[@]}";
    do
	    case "$arg" in
	        --tempdir)    source "${common}"/tempdir.sh    ;;
	        --traputils)  source "${common}"/traputils.sh  ;;
	        --stacktrace) source "${common}"/stacktrace.sh ;;
            *) echo "ERROR ${BASH_SOURCE[0]}: [SKIP] unknown switch=$arg" ;;
	    esac
    done

    return
}

##----------------##
##---]  MAIN  [---##
##----------------##
source "${BASH_SOURCE[0]%/*}/preserve_argv.sh" # pushd @ARGV

if [ $# -gt 0 ] && [ "$1" == '--common-args-begin--' ]; then
    shift # remove arg marker
fi

if [ $# -eq 0 ]; then
    # common.sh defaults
    set -- '--tempdir' '--traputils' '--stacktrace'
fi

__anon_func__ "$@"
unset __anon_func__
source "${BASH_SOURCE[0]%/*}/preserve_argv.sh" # popd @ARGV

[[ -v __DEBUG_COMMON__ ]] && echo " ** ${BASH_SOURCE[0]}: END"
: # NOP

# [EOF]
