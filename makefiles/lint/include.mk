# -*- makefile -*-
# -----------------------------------------------------------------------
# Copyright 2022 Open Networking Foundation (ONF) and the ONF Contributors
# -----------------------------------------------------------------------

$(if $(DEBUG),$(warning ENTER))

include $(ONF_MAKE)/lint/makefile.mk
include $(ONF_MAKE)/lint/python.mk
include $(ONF_MAKE)/lint/shell.mk
include $(ONF_MAKE)/lint/help.mk

ifdef YAML_FILES
  include $(ONF_MAKE)/lint/yaml/python.mk
else
  include $(ONF_MAKE)/lint/yaml/yamllint.mk
endif

$(if $(DEBUG),$(warning LEAVE))

# [EOF]
