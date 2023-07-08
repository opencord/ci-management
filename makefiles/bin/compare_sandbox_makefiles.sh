#!/bin/bash

function error
{
    echo "$*"
    exit 1
}

src="$HOME/projects/sandbox/onf-make/makefiles"
dst="$(realpath .)"

while [ $# -gt 0 ]; do
    fyl=$1; shift

    echo "FYL: $fyl"
    if [ -d "$fyl" ]; then
	readarray -t fyls < <(find "$fyl" -type f -print)
	[[ ${#@} -gt 0 ]] && fyls+=("$@")
	# declare -p fyls
	[[ ${#fyls} -gt 0 ]] && set -- "${fyls[@]}"
	continue
    fi

    src0="$src/$fyl"
    dst0="$dst/$fyl"

    [[ ! -e "$src0" ]] && error "File does not exist in src= $src0"
    [[ ! -e "$dst0" ]] && error "File does not exist in dst= $dst0"

    if ! diff -qr "$src0" "$dst0"; then
	emacs "$src0" "$dst0"
    fi
done
