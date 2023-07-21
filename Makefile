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

.PHONY: help clean help test
.DEFAULT_GOAL := help

##-------------------##
##---]  GLOBALS  [---##
##-------------------##

##--------------------##
##---]  INCLUDES  [---##
##--------------------##
include config.mk
include makefiles/include.mk
ONF_MAKEDIR ?= $(error ONF_MAKEDIR= is required)

# VENV_DIR      ?= venv-jjb
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

# -----------------------------------------------------------------------
# Intent: Generate pipeline jobs
# -----------------------------------------------------------------------
build : jjb-gen

##-------------------##
##---]  TARGETS  [---##
##-------------------##
## Display make help summary late
include $(ONF_MAKEDIR)/help/trailer.mk

# make help not widely in use yet so be explicit
help ::
	@echo
	@echo 'Usage: $(MAKE) help ...'
	@echo '  % make clean sterile'
	@echo '  % make lint lint-jjb'
	@echo '  % make build           # jjb-gen'
	@echo '  % make test'
	@echo '  % make help            # show all available targes'

# [EOF]
