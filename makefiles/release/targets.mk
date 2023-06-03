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
# Intent: Helper makefile target used to setup for a release
# -----------------------------------------------------------------------

$(if $(DEBUG),$(warning ENTER))

##-------------------##
##---]  GLOBALS  [---##
##-------------------##

# TODO: Library function  $(call mk-path,makefiles/release/targets.mk)
release-mk-top := $(abspath $(lastword $(MAKEFILE_LIST)))
release-mk-top := $(subst /makefiles/release/targets.mk,$(null),$(release-mk-top))

GIT	?= /usr/bin/env git

# fatal to make help
voltha-version ?= $(error $(MAKE) voltha-verison=voltha-x.yy is required)\

last-release  := voltha-2.11

## Known releases
versions += master
versions += voltha-2.12
versions += voltha-2.11
versions += voltha-2.8
versions += playground

##-------------------##
##---]  TARGETS  [---##
##-------------------##
all: help

## ---------------------------------------------------------------------------
## Intent: Create branch driven pipeline test jobs.
## ---------------------------------------------------------------------------
## Build these deps to create a new release area
create-jobs-release += create-jobs-release-certification
create-jobs-release += create-jobs-release-nightly
create-jobs-release += create-jobs-release-units

create-jobs-release : $(create-jobs-release)

	@echo
	$(GIT) status

## ---------------------------------------------------------------------------
## Intent: Create branch driven pipeline test jobs.
## ---------------------------------------------------------------------------
units-yaml := $(release-mk-top)/jjb/pipeline/voltha/$(voltha-version)
units-root := $(subst /$(voltha-version),$(null),$(units-yaml))
create-jobs-release-units : $(units-yaml)
$(units-yaml):

	@echo
	@echo "** Create branch driven pipeline: unit tests"
	$(HIDE)mkdir -vp $@
	rsync -r --checksum $(units-root)/master/. $@/.
	$(HIDE)/bin/ls -l $(units-root)

## ---------------------------------------------------------------------------
## Intent: Create branch driven nightly test jobs.
##   o Clone config for the last nightly release
##   o In-place edit to the latest version.
## ---------------------------------------------------------------------------
## NOTE: WIP - nightly jobs have not yet migrated from the mega job config file
## ---------------------------------------------------------------------------
nightly-dir  := $(release-mk-top)/jjb/voltha-test/voltha-nightly-jobs
nightly-yaml := $(nightly-dir)/$(voltha-version).yaml
nightly-tmpl := $(nightly-dir)/$(last-release).yaml

create-jobs-release-nightly : $(nightly-yaml)
$(nightly-yaml) : $(nightly-tmpl)

	@echo
	@echo "** Create branch driven pipeline: nightly tests"
	sed -e 's/$(last-release)/$(voltha-version)/g' $< > $@
	$(HIDE)/bin/ls -l $(dir $@)

## ---------------------------------------------------------------------------
## Intent: Create branch driven nightly test jobs.
## ---------------------------------------------------------------------------
$(nightly-tmpl):
	@echo "ERROR: Yaml template branch does not exist: $@"
	@echo 1

## ---------------------------------------------------------------------------
## Intent: Create branch driven nightly test jobs.
##   o Clone config for the last nightly release
##   o In-place edit to the latest version.
## ---------------------------------------------------------------------------
## NOTE: WIP - nightly jobs have not yet migrated from the mega job config file
## ---------------------------------------------------------------------------
certification-dir  := $(release-mk-top)/jjb/voltha-test/voltha-certification
certification-yaml := $(certification-dir)/$(voltha-version).yaml
certification-tmpl := $(certification-dir)/$(last-release).yaml

create-jobs-release-certification : $(certification-yaml)
$(certification-yaml) : $(certification-tmpl)

	@echo
	@echo "** Create branch driven pipeline: nightly tests"
	sed -e 's/$(last-release)/$(voltha-version)/g' $< > $@
	$(HIDE)/bin/ls -l $(dir $@)

## ---------------------------------------------------------------------------
## Intent: Create branch driven nightly test jobs.
## ---------------------------------------------------------------------------
$(certification-tmpl):
	@echo "ERROR: Yaml template branch does not exist: $@"
	@echo 1


## ---------------------------------------------------------------------------
## Intent: Create branch driven nightly test jobs.
## ---------------------------------------------------------------------------
sterile-create-jobs-release :
	$(RM) $(certification-yaml)
	$(RM) $(nightly-yaml)
	$(RM) -r $(units-yaml)

$(if $(DEBUG),$(warning LEAVE))

# [EOF]
