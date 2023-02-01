# -*- makefile -*-
# -----------------------------------------------------------------------
# Copyright 2017-2023 Open Networking Foundation (ONF) and the ONF Contributors
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
.PHONY: help help-summary help-simple help-verbose

##-------------------##
##---]  TARGETS  [---##
##-------------------##

## -----------------------------------------------------------------------
## Intent: Render topic/tool based makefile help
## -----------------------------------------------------------------------
## Three targets are used to render conditional makefile help
##    help-summary      A one-line target summary for the topic
##    help-simple       Common targets for the topic (lint-helm, build, test)
##    help-verbose      Exhaustive display of supported targets
## -----------------------------------------------------------------------
## [COOKBOOK]
##   help colon-colon   All 'help' targets are evaluated for 'make help'
##   help-banner        Display a usage banner for help
##   help-summary       Display all one-line topic summary help
##     [conditonal]
##   help-simple        Display all common topic makefile targets.
##   help-verbose       Exhaustive display of makefile target help.
##     VERBOSE=
## -----------------------------------------------------------------------
## [See Also] makefiles/gerrit/{include.mk, help.mk}
##   help-gerrit        Summary targets can always be used to display topic help
##   help-verbose       Exhaustive gerrit target display.
## -----------------------------------------------------------------------
help :: help-banner help-summary

## -----------------------------------------------------------------------
## Intent: Display a usage banner for help.  Target will be evaluated
##         before all other help display.
## -----------------------------------------------------------------------
help-banner:
	@echo "Usage: $(MAKE) [options] [target] ..."

## -----------------------------------------------------------------------
## Intent: Display extended help.
## -----------------------------------------------------------------------
## Question:
##   o Help display can be long based on volume of targets.
##   o Should a 3rd case be added to display:
##      - help-simple (one-liner help) by default
##      - conditional display of extended help:
##          - help-simple or help-verbose
##   o Current logic displays extended help by default.
## -----------------------------------------------------------------------
ifdef VERBOSE
  help :: help-verbose
else
  help :: help-simple
endif

## -----------------------------------------------------------------------
## Intent: Display simple extended target help
## -----------------------------------------------------------------------
help-simple ::
	@echo
	@echo '[VIEW]'
	@echo '  reload                 Setup to auto-reload sphinx doc changes in browser'
	@echo '  view-html              View generated documentation'
	@echo
	@echo '[TEST]'
	@echo '  test                   make lint linkcheck'
	@echo '  test-all               make all-generation-targets'

# [EOF]
