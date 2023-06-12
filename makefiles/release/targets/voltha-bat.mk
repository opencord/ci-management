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

## ---------------------------------------------------------------------------
## Intent: Create branch driven bat test jobs.
##   o Clone config for the last bat release
##   o In-place edit to the latest version.
## ---------------------------------------------------------------------------
## NOTE: WIP - bat jobs have not yet migrated from the mega job config file
## ---------------------------------------------------------------------------
voltha-bat-dir  := $(release-mk-top)/jjb/voltha-test/voltha-bat
voltha-bat-yaml := $(voltha-bat-dir)/$(voltha-version).yaml
voltha-bat-tmpl := $(voltha-bat-dir)/$(voltha-release-last).yaml

create-jobs-release-bat : $(voltha-bat-yaml)
$(voltha-bat-yaml) : $(voltha-bat-tmpl)

	@echo
	@echo "** Create branch driven pipeline: bat tests"
	sed -e 's/$(last-release)/$(voltha-version)/g' $< > $@
	$(HIDE)/bin/ls -l $(dir $@)

## ---------------------------------------------------------------------------
## Intent: Create branch driven bat test jobs.
## ---------------------------------------------------------------------------
$(voltha-bat-tmpl):
	@echo "ERROR: Yaml template branch does not exist: $@"
	@echo

## ---------------------------------------------------------------------------
## Intent: Create branch driven bat test jobs.
## ---------------------------------------------------------------------------
sterile-create-jobs-release-e2e :
	$(RM) $(voltha-bat-yaml)

$(if $(DEBUG),$(warning LEAVE))

# [EOF]
