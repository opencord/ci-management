# -*- makefile -*-
# -----------------------------------------------------------------------
# Copyright 2022-2024 Open Networking Foundation Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http:#www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# -----------------------------------------------------------------------
# SPDX-FileCopyrightText: 2022-2024 Open Networking Foundation Contributors
# SPDX-License-Identifier: Apache-2.0
# -----------------------------------------------------------------------

.PHONY: help clean help test
.DEFAULT_GOAL := help

##-------------------##
##---]  GLOBALS  [---##
##-------------------##

##--------------------##
##---]  INCLUDES  [---##
##--------------------##
# include config.mk
include lf/include.mk

ONF_MAKEDIR ?= $(error ONF_MAKEDIR= is required)

# [TODO]
# JJB_VERSION   ?= 4.1.0

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
	@printf '  %-33.33s %s\n' 'lint' \
	  'Invoke syntax checking targets'
	@printf '  %-33.33s %s\n' 'build' \
	  'Invoke jenkins-jobs to regenerate pipelines'
	@echo '  % make test'
	@echo '  % make help            # show all available targes'

	@echo
	@echo '[HELP: modifiers]'
	@printf '  %-33.33s %s\n' '{topic}-help' \
	  'Display extended help for individual makefile targets'
	@printf '  %-33.33s %s\n' 'help-verbose' \
	  'Display exhaustive help'

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
build-help :
	@printf '  %-33.33s %s\n' 'build' \
	  'Alias for $(MAKE) jjb-gen'
	@printf '  %-33.33s %s\n' 'jjb-gen' \
	  'Invoke jenkins-jobs to regenerate pipelines'

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
lint-help :
	@printf '  %-33.33s %s\n' 'lint' \
	  'Perform syntax checking on configured sources'
	@printf '  %-33.33s %s\n' 'lint-jjb' \
	  'Invoke jenkins-jobs to syntax check JJB source'

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
help-verbose :: help $(help-verbose)

# [EOF]
