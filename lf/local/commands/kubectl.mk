# -*- makefile -*-
# -----------------------------------------------------------------------
# Copyright 2024 Open Networking Foundation Contributors
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
# SPDX-FileCopyrightText: 2024 Open Networking Foundation Contributors
# SPDX-License-Identifier: Apache-2.0
# -----------------------------------------------------------------------
# Intent: Centralized logic for installing the kubectl command
# -----------------------------------------------------------------------

export .DEFAULT_GOAL := help

MAKEDIR ?= $(error MAKEDIR= is required)

# -----------------------------------------------------------------------
# Install command by version to prevent one bad download from
# taking all jobs out of action.  Do not rely on /usr/local/bin/kubectl,
# command can easily become stagnant.
# -----------------------------------------------------------------------

# Supported versions
kubectl-versions	+= v1.23
kubectl-versions	+= v1.30.3

# kubectl-ver         ?= v1.23#          # voltha v2.12 (?)
kubectl-ver         ?= v1.30.3#          # 2024-07-22: latest release
kubectl-ver		    ?= $(shell curl -L -s https://dl.k8s.io/release/stable.txt)
kubectl-ver			?= $(error kubectl-ver= is required)

kube-url			:= https://dl.k8s.io/release/$(kubectl-ver)/bin/linux/amd64/kubectl

# -----------------------------------------------------------------------
# Install the 'kubectl' tool if needed: https://github.com/boz/kubectl
#   o WORKSPACE - jenkins aware
#   o Default to /usr/local/bin/kubectl
#       + revisit this, system directories should not be a default path.
#       + requires sudo and potential exists for overwrite conflict.
# -----------------------------------------------------------------------
KUBECTL_PATH	?= $(if $(WORKSPACE),$(WORKSPACE)/bin,/usr/local/bin)
kubectl-cmd		?= $(KUBECTL_PATH)/kubectl
kubectl-ver-cmd	:= $(kubectl-cmd).$(kubectl-ver)

# -----------------------------------------------------------------------
# 1) Generic target for installing kubectl
# -----------------------------------------------------------------------
.PHONY: kubectl
kubectl : $(kubectl-cmd) $(kubectl-version)

# -----------------------------------------------------------------------
# 2) Activate by copying the version approved by voltha release into place.
#    bin/kubectl.123
#    bin/kubectl.456
#    cp bin/kubectl.123 bin/kubectl
# -----------------------------------------------------------------------
$(kubectl-cmd) : $(kubectl-ver-cmd)

	$(call banner-enter,Target $@ (ver=$(kubectl-ver)))
	ln -fns $< $@
	$(call banner-leave,Target $@ (ver=$(kubectl-ver)))

# -----------------------------------------------------------------------
# 3) Intent: Download versioned kubectl into the local build directory
# -----------------------------------------------------------------------
$(kubectl-ver-cmd):

#	$(call banner,(kubectl install: $(kubectl-ver)))
	@echo "kubectl install: $(kubectl-ver)"

	@mkdir --mode 0755 -p $(dir $@)

	curl \
	  --output $@ \
	  --location "$(kube-url)" \
	  --no-progress-meter

	@umask 0 && chmod 0555 $@

# -----------------------------------------------------------------------
# Intent: Display command version
# -----------------------------------------------------------------------
# NOTE:
#   - kubectl version requires connection to a running server.
#   - use a simple display answer to avoid installation failure source
# -----------------------------------------------------------------------
kubectl-version :

	@echo
	realpath --canonicalize-existing $(kubectl-cmd)

	@echo
	-$(kubectl-cmd) version

## -----------------------------------------------------------------------
## Intent: Display target help
## -----------------------------------------------------------------------
help::
	@printf '  %-33.33s %s\n' 'kubectl'       'Install the kubectl command'

	@$(foreach ver,$(kubectl-versions),\
	  @printf '  %-33.33s %s\n' 'kubectl-$(ver)' 'Install versioned kubectl' \
	)

	@printf '  %-33.33s %s\n' 'kubectl-version' \
	  'Display installed command version'

ifdef VERBOSE
	@echo "                  make kubectl KUBECTL_PATH="
endif

## -----------------------------------------------------------------------
## Intent: Remove binaries to force clean download and install
## -----------------------------------------------------------------------
clean ::
	$(RM) $(kubectl-cmd)

sterile :: clean
	$(RM) $(kubectl-ver-cmd)

# [SEE ALSO]
# https://kubernetes.io/docs/tasks/tools/install-kubectl-linux/

# [EOF]
