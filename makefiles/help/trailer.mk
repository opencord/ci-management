# -*- makefile -*-
# -----------------------------------------------------------------------
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
# -----------------------------------------------------------------------

# include this makefile late so text will be displayed at the end

help::
	@echo
	@echo '[CLEAN]'
	@echo '  clean                         Remove generated targets'
	@echo '  sterile                       Clean + remove virtual env interpreter install'

help::
	@echo
	@echo '[HELP]'
	@echo '  help                          Display program help'
	@echo '  help-variables                Display make variables that augment target behavior'
	@echo '  help-verbose                  Display additional targets and help'

include $(ONF_MAKE)/help/variables.mk

help ::
	@echo
	@echo '[NOTE: python 3.10+]'
	@echo '  The interpreter is not yet fully supported across foreign repositories.'
	@echo '  While working locally, if make fails to build a target try:'
	@echo '      $(MAKE) $${target} NO_OTHER_REPO_DOCS=1'

# [EOF]
