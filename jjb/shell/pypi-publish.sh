#!/usr/bin/env bash

# pypi-publish.sh - Publishes Python modules to PyPI
#
# Makes the following assumptions:
# - PyPI credentials are populated in ~/.pypirc
# - git repo is tagged with a SEMVER released version. If not, exit.
# - If required, Environmental variables can be set for:
#     PYPI_INDEX - name of PyPI index to use (see contents of ~/.pypirc for reference)
#     PYPI_MODULE_DIRS - pipe-separated list of modules to be uploaded
#     PYPI_PREP_COMMANDS - commands to run (in root directory) to prepare for sdist

set -eu -o pipefail

echo "Using twine version:"
twine --version

pypi_success=0

# environmental vars
WORKSPACE=${WORKSPACE:-.}
PYPI_PREP_COMMANDS=${PYPI_PREP_COMMANDS:-}
PYPI_INDEX=${PYPI_INDEX:-testpypi}
PYPI_MODULE_DIRS=${PYPI_MODULE_DIRS:-.}

# check that we're on a semver released version
GIT_VERSION=$(git tag -l --points-at HEAD)

# match bare versions or v-prefixed golang style version
if [[ "$GIT_VERSION" =~ ^v?([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]
then
  echo "git has a SemVer released version tag: '$GIT_VERSION', publishing to PyPI"
else
  echo "No SemVer released version tag found, exiting..."
  exit 0
fi

# Run commands if PYPI_PREP_COMMANDS if not null
if [[ -n "$PYPI_PREP_COMMANDS" ]]
then
  $PYPI_PREP_COMMANDS
fi

# iterate over $PYPI_MODULE_DIRS
# field separator is pipe character
IFS=$'|'
for pymod in $PYPI_MODULE_DIRS
do
  pymoddir="$WORKSPACE/$pymod"

  if [ ! -f "$pymoddir/setup.py" ]
  then
    echo "Directory with python module not found at '$pymoddir'"
    pypi_success=1
  else
    pushd "$pymoddir"

    echo "Building python module in '$pymoddir'"
    # Create source distribution
    python setup.py sdist

    # Upload to PyPI
    echo "Uploading to PyPI"
    twine upload -r "$PYPI_INDEX" dist/*

    popd
  fi
done

exit $pypi_success
