#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2018-2022 Open Networking Foundation
# SPDX-License-Identifier: Apache-2.0

set -eu -o pipefail

VERSIONFILE="" # file path to file containing version number
NEW_VERSION="" # version number found in $VERSIONFILE
TAG_VERSION="" # version file that might have a leading v to work around go mod funkyness

SEMVER_STRICT=${SEMVER_STRICT:-0} # require semver versions
DOCKERPARENT_STRICT=${DOCKERPARENT_STRICT:-1} # require semver versions on parent images in dockerfiles

releaseversion=0
fail_validation=0

# when not running under Jenkins, use current dir as workspace
WORKSPACE=${WORKSPACE:-.}
BASEDIR=${BASEDIR:-}

# cd to the code checkout location
cd "$WORKSPACE/$BASEDIR"

# find the version string in the repo, read into NEW_VERSION
# Add additional places NEW_VERSION could be found to this function
function read_version {
  if [ -f "VERSION" ]
  then
    NEW_VERSION=$(head -n1 "VERSION")
    VERSIONFILE="VERSION"

    # If this is a golang project, use funky v-prefixed versions
    if [ -f "Gopkg.toml" ] || [ -f "go.mod" ]
    then
      echo "go-based project found, using v-prefixed version for git tags: v${NEW_VERSION}"
      TAG_VERSION=v${NEW_VERSION}
    else
      TAG_VERSION=${NEW_VERSION}
    fi

  elif [ -f "package.json" ]
  then
    NEW_VERSION=$(python -c 'import json,sys;obj=json.load(sys.stdin); print obj["version"]' < package.json)
    TAG_VERSION=$NEW_VERSION
    VERSIONFILE="package.json"
  elif [ -f "pom.xml" ]
  then
    NEW_VERSION=$(xmllint --xpath '/*[local-name()="project"]/*[local-name()="version"]/text()' pom.xml)
    TAG_VERSION=$NEW_VERSION
    VERSIONFILE="pom.xml"
  else
    echo "ERROR: No versioning file found!"
    fail_validation=1
  fi
}

# check if the version is a released version
function check_if_releaseversion {
  if [[ "$NEW_VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]
  then
    echo "Version string '$NEW_VERSION' found in '$VERSIONFILE' is a SemVer released version!"
    releaseversion=1
  else
    if [ "$SEMVER_STRICT" -eq "1" ]
    then
      echo "Version string '$NEW_VERSION' in '$VERSIONFILE' is not a SemVer released version, SEMVER_STRICT enabled, failing!"
      fail_validation=1
    else
      echo "Version string '$NEW_VERSION' in '$VERSIONFILE' is not a SemVer released version, skipping."
    fi
  fi
}

# check if the version is already a tag in git
function is_git_tag_duplicated {
  for existing_tag in $existing_tags
  do
    if [ "$TAG_VERSION" = "$existing_tag" ]
    then
      echo "ERROR: Duplicate tag: $existing_tag"
      fail_validation=2
    fi
  done
}

# from https://github.com/cloudflare/semver_bash/blob/master/semver.sh
function semverParseInto() {
    local RE='[^0-9]*\([0-9]*\)[.]\([0-9]*\)[.]\([0-9]*\)\([0-9A-Za-z-]*\)'
    #MAJOR
    eval $2=`echo $1 | sed -e "s#$RE#\1#"`
    #MINOR
    eval $3=`echo $1 | sed -e "s#$RE#\2#"`
    #MINOR
    eval $4=`echo $1 | sed -e "s#$RE#\3#"`
    #SPECIAL
    eval $5=`echo $1 | sed -e "s#$RE#\4#"`
}

# if it's a -dev version check if a previous tag has been created (to avoid going from 2.7.0-dev to 2.7.1-dev)
function is_valid_version {
  local MAJOR=0 MINOR=0 PATCH=0 SPECIAL=""
  local C_MAJOR=0 C_MINOR=0 C_PATCH=0 C_SPECIAL="" # these are used in the inner loops to compare

  semverParseInto $NEW_VERSION MAJOR MINOR PATCH SPECIAL

  found_parent=false

  # if minor == 0, check that there was a release with MAJOR-1.X.X
  if [[ "$MINOR" == 0 ]]; then
    new_major=$(( $MAJOR - 1 ))
    parent_version="$new_major.x.x"
    for existing_tag in $existing_tags
    do
      semverParseInto $existing_tag C_MAJOR C_MINOR C_PATCH C_SPECIAL
      if [[ "$new_major" == "$C_MAJOR" ]]; then
        found_parent=true
      fi
    done
  fi

  # if patch == 0, check that there was a release with MAJOR.MINOR-1.X
  if [[ "$PATCH" == 0 ]]; then
    new_minor=$(( $MINOR - 1 ))
    parent_version="$MAJOR.$new_minor.x"
    for existing_tag in $existing_tags
    do
      semverParseInto $existing_tag C_MAJOR C_MINOR C_PATCH C_SPECIAL
      if [[ "$new_minor" == "$C_MINOR" ]]; then
        found_parent=true
      fi
    done
  fi

  # if patch != 0 check that there was a release with MAJOR.MINOR.PATCH-1
  if [[ "$PATCH" != 0 ]]; then
    new_patch=$(( $PATCH - 1 ))
    parent_version="$MAJOR.$MINOR.$new_patch"
    for existing_tag in $existing_tags
    do
      semverParseInto $existing_tag C_MAJOR C_MINOR C_PATCH C_SPECIAL
      if [[ "$MAJOR" == "$C_MAJOR" && "$MINOR" == "$C_MINOR" && "$new_patch" == "$C_PATCH" ]]
      then
        found_parent=true
      fi
    done
  fi

  # if we are the beginning the is no parent, but that's fine
  if [[ "$MAJOR" == 0 ]]; then
    found_parent=true
  fi

  if [[ $found_parent == false ]]; then
    echo "Invalid $NEW_VERSION version. Expected parent version $parent_version does not exist."
    fail_validation=1
  fi
}

# check if Dockerfiles have a released version as their parent
function dockerfile_parentcheck {
  if [ "$DOCKERPARENT_STRICT" -eq "0" ];
  then
    echo "DOCKERPARENT_STRICT is disabled - skipping parent checks"
  else
    while IFS= read -r -d '' dockerfile
    do
      echo "Checking dockerfile: '$dockerfile'"

      # split on newlines
      IFS=$'\n'
      df_parents=($(grep "^FROM" "$dockerfile"))

      # check all parents in the Dockerfile
      for df_parent in "${df_parents[@]}"
      do

        df_pattern="[FfRrOoMm] +(--platform=[^ ]+ +)?([^@: ]+)(:([^: ]+)|@sha[^ ]+)?"
        if [[ "$df_parent" =~ $df_pattern ]]
        then

          p_image="${BASH_REMATCH[2]}"
          p_sha=${BASH_REMATCH[3]}
          p_version="${BASH_REMATCH[4]}"

          echo "IMAGE: '${p_image}'"
          echo "VERSION: '$p_version'"
          echo "SHA: '$p_sha'"

          if [[ "${p_image}" == "scratch" ]]
          then
            echo "  OK: Using the versionless 'scratch' parent: '$df_parent'"
          elif [[ "${p_image}:${p_version}" == "gcr.io/distroless/static:nonroot" ]]
          then
            echo "  OK: Using static distroless image with nonroot: '${p_image}:${p_version}'"
          elif [[ "${p_version}" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]
          then
            echo "  OK: Parent '$p_image:$p_version' is a released SemVer version"
          elif [[ "${p_sha}" =~ ^@sha256:[0-9a-f]{64}.*$ ]]
          then
            # allow sha256 hashes to be used as version specifiers
            echo "  OK: Parent '$p_image$p_sha' is using a specific sha256 hash as a version"
          elif [[ "${p_version}" =~ ^.*([0-9]+)\.([0-9]+).*$ ]]
          then
            # handle non-SemVer versions that have a Major.Minor version specifier in the name
            #  'ubuntu:16.04'
            #  'postgres:10.3-alpine'
            #  'openjdk:8-jre-alpine3.8'
            echo "  OK: Parent '$p_image:$p_version' is using a non-SemVer, but sufficient, version"
          elif [[ -z "${p_version}" ]]
          then
            echo "  ERROR: Parent '$p_image' is NOT using a specific version"
            fail_validation=1
          else
            echo "  ERROR: Parent '$p_image:$p_version' is NOT using a specific version"
            fail_validation=1
          fi

        else
          echo "  ERROR: Couldn't find a parent image in $df_parent"
        fi

      done

    done  < <( find "${WORKSPACE}" -name 'Dockerfile*' ! -path "*/vendor/*" ! -name "*dockerignore" -print0 )
  fi
}

# Start of actual code
echo "Checking git repo with remotes:"
git remote -v

echo "Branches:"
branches=$(git branch -v)
echo $branches

echo "Existing git tags:"
existing_tags=$(git tag -l)
echo $existing_tags

read_version
is_valid_version
check_if_releaseversion

# perform checks if a released version
if [ "$releaseversion" -eq "1" ]
then
  is_git_tag_duplicated
  dockerfile_parentcheck
fi

exit $fail_validation
