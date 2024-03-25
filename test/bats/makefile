# -*- makefile -*-
# -----------------------------------------------------------------------
# Copyright 2024 Open Networking Foundation Contributors
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
# SPDX-FileCopyrightText: 2024 Open Networking Foundation Contributors
# SPDX-License-Identifier: Apache-2.0
# -----------------------------------------------------------------------
# Intent: This test suite will validate repo:helm-repo-tools scripts
# -----------------------------------------------------------------------

null	:=#
space	:=$(null) $(null)
HIDE	?= @

src = $(wildcard *.bats)
bats := $(subst .bats,$(null),$(src))
.PHONY: $(bats)

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
all:

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
.PHONY: test
test += test-bats
test : $(test)

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
.PHONY: test-bats
test-bats : $(bats)

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
$(bats) :
	$(HIDE)./$@.bats

## -----------------------------------------------------------------------
## Intent: Display target help
## -----------------------------------------------------------------------
help ::
	@echo 'Usage: $(MAKE) [options] [target] ...'
	@printf '  %-33.33s %s\n' \
	  'test' 'Launch available test suites'
	@printf '  %-33.33s %s\n' 'test-bats' \
	  'Launch available BATS shell test suites'

	@echo
	@echo '[TEST: by name]'
	@$(MAKE) --no-print-directory help-bats

	@echo
	@echo '[HELP]'
	@printf '  %-33.33s %s\n' 'help'      'Display target usage'
	@printf '  %-33.33s %s\n' 'help-bats' 'Display a list of test suites'

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
help-bats := $(addprefix help--,$(bats))
help-bats : $(help-bats)
$(help-bats) :
	@printf '  $(MAKE) %-33.33s %s\n' "$(lastword $(subst -,$(space),$@))" ''

# [EOF]
