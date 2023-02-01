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

NO-LINT-MAKE  := true
NO-LINT-SHELL := true

##--------------------##
##---]  INCLUDES  [---##
##--------------------##
include $(MAKEDIR)/include.mk

VENV_DIR      ?= venv-jjb
# JJB_VERSION   ?= 4.1.0
JOBCONFIG_DIR ?= job-configs

$(JOBCONFIG_DIR):
	mkdir $@

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
.PHONY: test
test: $(venv-activate-script) $(JOBCONFIG_DIR)
	$(activate) \
	&& pipdeptree \
	&& jenkins-jobs -l DEBUG test --recursive --config-xml -o "$(JOBCONFIG_DIR)" jjb/ ;

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
.PHONY: clean
clean:
	$(RM) -r $(JOBCONFIG_DIR)

include $(ONF_MAKE)/help/trailer.mk

# [EOF]
