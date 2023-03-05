# -*- makefile -*-
# -----------------------------------------------------------------------
# Copyright 2022-2023 Open Networking Foundation (ONF) and the ONF Contributors
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

# Makefile for testing JJB jobs in a virtualenv
.PHONY: help clean help test
.DEFAULT_GOAL := help

##-------------------##
##---]  GLOBALS  [---##
##-------------------##
TOP          ?= .
MAKEDIR      ?= $(TOP)/makefiles
export SHELL := bash -e -o pipefail#    # [TODO] remove once set -u cleaned up

##--------------------##
##---]  INCLUDES  [---##
##--------------------##
include config.mk
include $(MAKEDIR)/include.mk

VENV_DIR      ?= venv-jjb
JJB_VERSION   ?= 2.8.0
# JJB_VERSION   ?= 4.1.0
JOBCONFIG_DIR ?= job-configs

# -----------------------------------------------------------------------
# horrible dep: (ie -- .PHONY: $(JOBCONFIG_DIR))
#   o Directory inode always changing due to (time-last-accessed++).
#   o Dependent Targets always re-made due to setale dependency.
#   o Use file inside directory as dep rather than a directory.
# -----------------------------------------------------------------------
$(JOBCONFIG_DIR):
	mkdir $@

##-------------------##
##---]  TARGETS  [---##
##-------------------##
include $(MAKEDIR)/targets/check.mk
include $(MAKEDIR)/targets/tox.mk#             # python unit testing
include $(MAKEDIR)/targets/test.mk

## Display make help late
include $(ONF_MAKE)/help/trailer.mk

# [EOF]
