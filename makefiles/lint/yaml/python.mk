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
# SPDX-FileCopyrightText: 2022-2023 Open Networking Foundation (ONF) and the ONF Contributors
# SPDX-License-Identifier: Apache-2.0
# -----------------------------------------------------------------------

YAML_FILES ?= $(error YAML_FILES= is required)

lint-yaml-dep = $(addsuffix ^lint-yaml,$(YAML_FILES))
lint-yaml-src = $(firstword $(subst ^,$(space),$(1)))

##-------------------##
##---]  TARGETS  [---##
##-------------------##
.PHONY : lint-yaml
lint   : lint-yaml

lint-yaml: $(venv-activate)
lint-yaml: $(lint-yaml-dep)

## -----------------------------------------------------------------------
## Intent: Perform a lint check on yaml sources
## -----------------------------------------------------------------------
$(lint-yaml-dep):
	$(vst-env) && yamllint -s $(call lint-yaml-src,$@)

## -----------------------------------------------------------------------
## Intent: Display command help
## -----------------------------------------------------------------------
help-summary ::
	@echo '  lint-yaml            Syntax check yaml sources (python -M yaml)'

# [EOF]
