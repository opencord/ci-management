# -*- makefile -*-

version += master
version += voltha-2.12
version += voltha-2.11
version += voltha-2.8
version += playground

all: $(version)

$(version):
	mkdir -p $(version)
	rsync -rv --checksum master/. $@/.

# [EOF]
