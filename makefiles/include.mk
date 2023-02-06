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

# OPT_ROOT    ?= /opt/trainlab/current
# OPT_MAKEDIR := $(OPT_ROOT)/makefiles
# MAKEDIR     ?= $(OPT_MAKEDIR)

ONF_MAKE ?= $(MAKEDIR)# fix this -- two distinct makefiles/ directories are needed
ONF_MAKE ?= $(error ONF_MAKE= is required)

include $(ONF_MAKE)/consts.mk
include $(ONF_MAKE)/help/include.mk

include $(ONF_MAKE)/virtualenv.mk#        # lint-{jjb,python} depends on venv
include $(ONF_MAKE)/lint/include.mk
include $(ONF_MAKE)/git-submodules.mk
include $(ONF_MAKE)/todo.mk
include $(ONF_MAKE)/help/variables.mk

$(if $(DEBUG),$(warning LEAVE))

# [EOF]
