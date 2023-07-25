#!/bin/bash
## -----------------------------------------------------------------------
## Intent: 
## -----------------------------------------------------------------------

##-------------------##
##---]  GLOBALS  [---##
##-------------------##
set -eu -o pipefail
umask 0

## -----------------------------------------------------------------------
## Intent: Display a message then exit with non-zero shell exit status.
## -----------------------------------------------------------------------
function error()
{
    echo "** ERROR: $*"
    exit 1
}

## -----------------------------------------------------------------------
## Intent: Display program usage
## -----------------------------------------------------------------------
function usage
{
    cat <<EOH
Usage: $0
  --cmd       Kind command name to download (w/arch type)
  --dir       Target bin directory to download into
  --ver       Kind command version to download (default=v0.11.0)

[MODEs]
  --clean     Clean download, remove kind binary if it exists.
EOH
    exit 1
}

##----------------##
##---]  MAIN  [---##
##----------------##

WORKSPACE="${WORKSPACE:-$(/bin/pwd)}"
dir="$WORKSPACE/bin"

bin='kind-linux-amd64'
ver='v0.11.0'

while [ $# -gt 0 ]; do
    arg="$1"; shift
    case "$arg" in
	-*clean) declare -i clean_mode=1 ;;
	-*cmd) bin="$1";s shift ;; # cmd-by-arch
	-*dir) dir="$1"; shift  ;; # target dir
	-*help) usage; exit 0 ;;
	-*ver) ver="$1"; shift  ;; # version
	*) echo "[SKIP] Unknown argument [$arg]" ;;
    esac
done


cmd="$dir/kind"
[[ -v clean_mode ]] && /bin/rm -f "$cmd"

if [ ! -f "$cmd" ]; then
    mkdir -p "$dir"
    pushd "$dir" || error "pushd $dir failed"
    echo "** ${0##*/}: Download ${bin} ${ver}"

    curl --silent -Lo ./kind "https://kind.sigs.k8s.io/dl/${ver}/${bin}"
    readarray -t file_info < <(file "$cmd")

    ## Validate binary
    case "${file_info[@]}" in
	*ASCII*)
	    echo "ERROR: kind command is invalid"
	    set -x; cat "$cmd"; set +x
	    echo
	    /bin/rm -f "$cmd"
	    ;;
	*) chmod +x ./kind ;;
    esac

    popd         || error "popd $dir failed"
fi

echo "Kind command version: $($cmd --version)"

# [EOF]
