# -*- sh -*-
## -----------------------------------------------------------------------
## Intent  : Register an interrupt handler to generate a stack trace
##           whenever shell commands fail or prematurely exist.
## Usage   : source stacktrace.sh
## See Also: traputils.sh
##           https://gist.github.com/ahendrix/7030300
## -----------------------------------------------------------------------
## set -e silently exiting on error is less than helpful.
## Use a call stack function to expose problem source.
## -----------------------------------------------------------------------

## -----------------------------------------------------------------------
## Intent: Trap/exit on error displaying context on the way out.
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
}

##----------------##
##---]  MAIN  [---##
##----------------##

# trap ERR to provide an error handler whenever a command exits nonzero
#  this is a more verbose version of set -o errexit
trap 'errexit' ERR
# trap 'errexit' EXIT
# trap 'errexit' DEBUG

# setting errtrace allows our ERR trap handler to be propagated to functions,
#  expansions and subshells
set -o errtrace

## Unit tests may need to disable interrupts to avoid control loss during segv
# source $(dirname ${BASH_SOURCE})/traputils.sh
source "${BASH_SOURCE[0]/stacktrace.sh/traputils.sh}"

## -----------------------------------------------------------------------
## Colon is a shell NOP operator that will set and return $?==0 to caller.
## Sourced scripts adding ':' as the final operator helps prevent an ugly
## manifestation.  Syntax errors normally will cause early script
## termination with no context, NOP allows control to return back to the
## caller where stacktrace can properly document the entry point.
## -----------------------------------------------------------------------

: # NOP w/side effects

# EOF
