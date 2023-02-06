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
#
# SPDX-FileCopyrightText: 2022 Open Networking Foundation (ONF) and the ONF Contributors
# SPDX-License-Identifier: Apache-2.0
# -----------------------------------------------------------------------

$(if $(DEBUG),$(warning ENTER))

GIT ?= git

## -----------------------------------------------------------------------
## Intent: Checkout submodules required by ci-management
## -----------------------------------------------------------------------
submodule-repo := $(null)
submodule-repo += global-jjb
submodule-repo += lf-ansible
submodule-repo += packer

submodule-deps := $(null)
submodule-deps += submodules#     # named pseudo target
submodule-deps += $(submodule-repos)

.PHONY: $(submodule-deps)
$(submodule-deps):
	$(GIT) submodule init
	$(GIT) submodule update

## -----------------------------------------------------------------------
## Intent: Revert sandbox to a pristine state.
## -----------------------------------------------------------------------
sterile ::
	$(RM) -r $(submodule-repos)

        # FIXME:
        #   o restore hierarchy to avoid git status 'deleted:'
        #   o remove: externals should not be under revision control
	$(GIT) co $(submodule-repos)

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
help ::
	@echo
	@echo '[GIT-SUBMODULES]'
	@echo '  submodules     Checkout dependent git submodules'
  ifdef VERBOSE
	@echo '  global-jjb     Checkout ci-management submodule global-jjb'
	@echo '  lf-ansible     Checkout ci-management submodule lf-ansible'
	@echo '  packer         Checkout ci-management submodule packer'
  endif

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
todo ::
	@echo "Generalize logc, update to depend on .git/ rather than named targets."

$(if $(DEBUG),$(warning LEAVE))

# [EOF]
