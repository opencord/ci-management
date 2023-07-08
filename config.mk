# -*- makefile -*-
# -----------------------------------------------------------------------
# Copyright 2023 Open Networking Foundation (ONF) and the ONF Contributors
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
# -----------------------------------------------------------------------

--repo-name-- := ci-management
--repo-name-- ?= $(error --repo-name--= is required)

##--------------------------------##
##---]  Disable lint targets  [---##
##--------------------------------##
# NO-LINT-DOC8      := true  **
# NO-LINT-GOLANG    := true  **
NO-LINT-GROOVY      := true#               # Note[1]
# NO-LINT-JJB       := true#               # Note[2]
# NO-LINT-JSON      := true#               # Note[1]
NO-LINT-MAKEFILE    := true#               # Note[1]
NO-LINT-REUSE       := true                # License check
# NO-LINT-ROBOT     := true  **
NO-LINT-SHELL       := true#               # Note[1]
NO-LINT-YAML        := true#               # Note[1]

# NO-LINT-PYTHON    := true#               # Note[1]
# NO-LINT-PYLINT    := true#               # Note[1]
# NO-LINT-TOX       := true#               # Note[1]

# Note[1] - A boatload of source to cleanup prior to enable.
# Note[2] - No sources available

##---------------------------------##
##---] Conditional make logic  [---##
##---------------------------------##
# USE-ONF-DOCKER-MK    := true
# USE-ONF-GERRIT-MK    := true
# USE-ONF-GIT-MK       := true
USE-ONF-JJB-MK       := ture

##----------------------##
##---]  Debug Mode  [---##
##----------------------##
# export DEBUG           := 1      # makefile debug
# export DISTUTILS_DEBUG := 1      # verbose: pip
# export DOCKER_DEBUG    := 1      # verbose: docker
# export VERBOSE         := 1      # makefile debug

##-----------------------------------##
##---]  JJB/Jenkins Job Builder  [---##
##-----------------------------------##
JJB_VERSION   ?= 2.8.0
JOBCONFIG_DIR ?= job-cofigs

##-----------------------------------##
##---]  Find command exclusions  [---##
##-----------------------------------##
onf-excl-dirs := $(null)
onf-excl-dirs += .venv#      # $(venv-name)
onf-excl-dirs += lf-ansible
onf-excl-dirs += packer
onf-excl-dirs += patches
onf-excl-dirs += .tox

onf-excl-dirs ?= $(error onf-excl-dirs= is required)

# [EOF]
