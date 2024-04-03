#!/bin/bash
## -----------------------------------------------------------------------
## Intent: An example script showing how to source common libraries
##    and produce a stack trace on script error/exit
## -----------------------------------------------------------------------

declare -g fatal=1 # assign to view stack trace

echo "$0: ENTER"

echo "$0: Source library shell includes"

declare -g pgmdir="${0%/*}" # dirname($script)
declare -a common_args=()
common_args+=('--common-args-begin--')
# common_args+=('--traputils')
# common_args+=('--stacktrace')
# common_args+=('--tempdir')
source "${pgmdir}/common.sh" "${common_args[@]}"

echo "$0: define foo(), bar() & tans()"

function foo()
{
    echo "${FUNCNAME}: hello - stack_frame[1]"
    bar
}

function bar()
{
    echo "${FUNCNAME}: hello - stack_frame[2]"
    tans
}

function tans()
{
    declare -g fatal
    echo "${FUNCNAME}: early exit for stacktrace"
    [[ $fatal -eq 1 ]] && exit 1
    return
}

echo "$0: calling foo() for a stack trace"
foo

echo "$0: ENTER"

## -----------------------------------------------------------------------
# % ./example.sh
# ./example.sh: ENTER
# ./example.sh: Source library shell includes
# ./example.sh: define foo(), bar() & tans()
# ./example.sh: calling foo() for a stack trace
# foo: hello - stack_frame[1]
# bar: hello - stack_frame[2]
# tans: early exit for stacktrace
#
# OFFENDER: ./example.sh:1
# ERROR: 'exit 1' exited with status 1
# Call tree:
# 1: ./example.sh:25 tans(...)
# 2: ./example.sh:19 bar(...)
# 3: ./example.sh:37 foo(...)
# Exiting with status 1

# [EOF]
