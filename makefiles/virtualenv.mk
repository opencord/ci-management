# -*- makefile -*-
## -----------------------------------------------------------------------
# Copyright 2017-2023 Open Networking Foundation
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
## -----------------------------------------------------------------------

$(if $(DEBUG),$(warning ENTER))

venv-name            ?= .venv
venv-abs-path        := $(PWD)/$(venv-name)

venv-activate-script := $(venv-name)/bin/activate#        # dependency
activate             ?= source $(venv-activate-script)#   # cmd invocation

## -----------------------------------------------------------------------
## Intent: Activate script path dependency
## Usage:
##    o place on the right side of colon as a target dependency
##    o When the script does not exist install the virtual env and display.
## -----------------------------------------------------------------------
$(venv-activate-script):
	virtualenv -p python3 $(venv-name)\
  && source $(venv-name)/bin/activate\
  && python -m pip install --upgrade pip\
  && pip install --upgrade setuptools\
  && { [[ -r requirements.txt ]] && python -m pip install -r requirements.txt; }\
  && python --version

## -----------------------------------------------------------------------
## Intent: Explicit named installer target w/o dependencies.
##         Makefile targets should depend on venv-activate-script.
## -----------------------------------------------------------------------
venv: $(venv-activate-script)

## -----------------------------------------------------------------------
## Intent: Revert installation to a clean checkout
## -----------------------------------------------------------------------
sterile :: clean
	$(RM) -r "$(venv-abs-path)"

## -----------------------------------------------------------------------
## -----------------------------------------------------------------------
help ::
	@echo
	@echo '[VIRTUAL ENV]'
	@echo '  venv-name=          Subdir name for virtualenv install'
	@echo '  venv-activate-script: make macro name'
	@echo '      $$(target) dependency    : install python virtualenv'
	@echo '      source $$(macro) && cmd  : configure env and run cmd'

$(if $(DEBUG),$(warning LEAVE))

# [EOF]
