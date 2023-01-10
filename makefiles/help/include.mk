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

# Parent makefile should include this early so help
# message will be prefixed by a usage statement.
help ::
	@echo "Usage: $(MAKE) [options] [target] ..."
	@echo
	@echo '[Virtual Env]'
	@echo '  venv           Create a python virtual environment'
	@echo "  $(VENV_NAME)"
	@echo
	@echo '[CLEAN]'
	@echo '  clean          Remove generated targets'
	@echo '  sterile        clean + remove virtual env interpreter install'
	@echo
	@echo '[VIEW]'
	@echo '  reload         Setup to auto-reload sphinx doc changes in browser'
	@echo '  view-html      View generated documentation'
	@echo
	@echo '[TEST]'
	@echo '  test           make lint linkcheck'
	@echo '  test-all       make all-generation-targets'
	@echo
	@echo '[HELP]'
	@echo '  help           Display program help'
	@echo '  help-verbose   Display additional targets and help'

# [EOF]
