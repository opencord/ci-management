# -*- makefile -*-
# -----------------------------------------------------------------------
# Copyright 2017-2024 Open Networking Foundation (ONF) and the ONF Contributors
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

$(if $(DEBUG),$(warning ENTER))

##-------------------##
##---]  GLOBALS  [---##
##-------------------##
$(if $(UNSTABLE),$(eval lint-python-all := true))

include $(ONF_MAKEDIR)/lint/python/find_utils.mk
include $(ONF_MAKEDIR)/lint/python/flake8.mk
include $(ONF_MAKEDIR)/lint/python/pylint.mk

$(if $(DEBUG),$(warning LEAVE))

# [EOF]
