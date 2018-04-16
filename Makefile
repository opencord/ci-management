# Makefile for testing JJB jobs in a virtualenv

.PHONY: test clean

VENV_DIR      ?= venv-jjb
JJB_VERSION   ?= 2.0.3
JOBCONFIG_DIR ?= job-configs

$(VENV_DIR):
	@echo "Setting up virtualenv for JJB testing"
	virtualenv $@
	$@/bin/pip install jenkins-job-builder==$(JJB_VERSION) pipdeptree

$(JOBCONFIG_DIR):
	mkdir $@

lint:
	yamllint -c yamllint.conf jjb/

test: $(VENV_DIR) $(JOBCONFIG_DIR)
	source $(VENV_DIR)/bin/activate ; \
	pipdeptree ; \
	jenkins-jobs -l DEBUG test --recursive -o $(JOBCONFIG_DIR) jjb/ ;

clean:
	rm -rf $(VENV_DIR) $(JOBCONFIG_DIR)
