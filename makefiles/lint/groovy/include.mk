# -*- makefile -*-
# -----------------------------------------------------------------------
# Copyright 2022-2024 Open Networking Foundation (ONF) and the ONF Contributors
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

##-------------------##
##---]  GLOBALS  [---##
##-------------------##

groovy-check      := npm-groovy-lint

groovy-check-args := $(null)
# groovy-check-args += --loglevel info
# groovy-check-args += --ignorepattern
# groovy-check-args += --verbose

##-------------------##
##---]  TARGETS  [---##
##-------------------##
ifndef NO-LINT-GROOVY
  lint : lint-groovy
endif

## -----------------------------------------------------------------------
## All or on-demand
##   make lint-groovy BY_SRC="a/b/c.groovy d/e/f.groovy"
## -----------------------------------------------------------------------
ifdef LINT_SRC
  lint-groovy : lint-groovy-src
else
  lint-groovy : lint-groovy-all
endif

## -----------------------------------------------------------------------
## Intent: Perform a lint check on command line script sources
## -----------------------------------------------------------------------
lint-groovy-all:
	$(groovy-check) --version
	@echo
	$(HIDE)$(env-clean) find . -iname '*.groovy' -print0 \
  | $(xargs-n1) $(groovy-check) $(groovy-check-args)

## -----------------------------------------------------------------------
## Intent: Perform lint check on a named list of files
## -----------------------------------------------------------------------
BY_SRC ?= $(error $(MAKE) $@ BY_SRC= is required)
lint-groovy-src:
	$(groovy-check) --version
	@echo
#	$(foreach fyl,$(BY_SRC),$(groovy-check) $(groovy-check-args) $(fyl))
	$(groovy-check) $(groovy-check-args) $(BY_SRC)

## -----------------------------------------------------------------------
## Intent: Perform lint check on a named list of files
## -----------------------------------------------------------------------
BYGIT = $(shell git diff --name-only HEAD | grep '\.groovy')
lint-groovy-mod:
	$(groovy-check) --version
	@echo
	$(foreach fyl,$(BYGIT),$(groovy-check) $(groovy-check-args) $(fyl))

## -----------------------------------------------------------------------
## Intent: Display command help
## -----------------------------------------------------------------------
help-summary ::
	@echo '  lint-groovy          Conditionally lint groovy source'
	@echo '      BY_SRC=a/b/c.groovy d/e/f.groovy'
  ifdef VERBOSE
	@echo '  lint-groovy-all    Lint all available sources'
	@echo '  lint-groovy-mod    Lint locally modified (git status)'
	@echo '  lint-groovy-src    Lint individually (BY_SRC=list-of-files)'
  endif
# [EOF]
