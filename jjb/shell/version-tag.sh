#!/usr/bin/env bash

# Copyright 2018-2022 Networking Foundation
#
# SPDX-License-Identifier: Apache-2.0

# version-tag.sh
# Tags a git commit with the SemVer version discovered within the commit,
# if the tag doesn't already exist. Ignore non-SemVer commits.

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
    exit 1
  fi
}

# check if the version is a released version
function check_if_releaseversion {
  if [[ "$NEW_VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]
  then
    echo "Version string '$NEW_VERSION' in '$VERSIONFILE' is a SemVer released version!"
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
  for existing_tag in $(git tag)
  do
    if [ "$TAG_VERSION" = "$existing_tag" ]
    then
      echo "ERROR: Duplicate tag: $existing_tag"
      exit 2
    fi
  done
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

# create a git tag
function create_git_tag {
  echo "Creating git tag: $TAG_VERSION"
  git checkout "$GERRIT_PATCHSET_REVISION"

  git config --global user.email "do-not-reply@opennetworking.org"
  git config --global user.name "Jenkins"

  git tag -a "$TAG_VERSION" -m "Tagged by CORD Jenkins version-tag job: $BUILD_NUMBER, for Gerrit patchset: $GERRIT_CHANGE_NUMBER"

  echo "Tags including new tag:"
  git tag -n

  git push origin "$TAG_VERSION"
}

echo "Checking git repo with remotes:"
git remote -v

echo "Branches:"
git branch -v

echo "Existing git tags:"
git tag -n

read_version
check_if_releaseversion

# perform checks if a released version
if [ "$releaseversion" -eq "1" ]
then
  is_git_tag_duplicated
  dockerfile_parentcheck

  if [ "$fail_validation" -eq "0" ]
  then
    create_git_tag
  else
    echo "ERROR: commit merged but failed validation, not tagging!"
  fi
fi

exit $fail_validation
