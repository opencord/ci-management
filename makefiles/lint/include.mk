# -*- makefile -*-
# -----------------------------------------------------------------------
# Copyright 2022 Open Networking Foundation (ONF) and the ONF Contributors
# -----------------------------------------------------------------------

$(if $(DEBUG),$(warning ENTER))

help ::
	@echo
	@echo "[LINT]"

include $(ONF_MAKE)/lint/groovy.mk
include $(ONF_MAKE)/lint/jjb.mk
include $(ONF_MAKE)/lint/json.mk
include $(ONF_MAKE)/lint/makefile.mk
include $(ONF_MAKE)/lint/python.mk
include $(ONF_MAKE)/lint/shell.mk
include $(ONF_MAKE)/lint/yaml.mk

include $(ONF_MAKE)/lint/help.mk

$(if $(DEBUG),$(warning LEAVE))

# [EOF]
