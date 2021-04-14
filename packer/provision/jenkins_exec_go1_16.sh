#!/usr/bin/env bash

set -exu -o pipefail

# create directories
export GOPATH=/usr/local/go
export PATH=$PATH:/usr/lib/go/bin:$GOPATH/bin
mkdir -p "$GOPATH"

# converters for unit/coverage tests
go get -v github.com/t-yuki/gocover-cobertura
go get -v github.com/jstemmer/go-junit-report

# github-release - uploader for github artifacts
go get -v github.com/github-release/github-release

# dep for go package dependencies w/versioning, version 0.5.2, adapted from:
#  https://golang.github.io/dep/docs/installation.html#install-from-source
go get -d -u github.com/golang/dep
pushd $(go env GOPATH)/src/github.com/golang/dep
  git checkout "0.5.2"
  go install -ldflags="-X main.version=0.5.2" ./cmd/dep
popd

# golangci-lint for testing
#  https://github.com/golangci/golangci-lint#local-installation
GO111MODULE=on go get github.com/golangci/golangci-lint/cmd/golangci-lint@v1.17.1

# protoc-gen-go - Golang protbuf compiler extension for protoc (installed
# below)
go get -d -u github.com/golang/protobuf/protoc-gen-go
pushd $(go env GOPATH)/src/github.com/golang/protobuf
  git checkout "v1.3.1"
  go install ./protoc-gen-go
popd

# NOTE - on run of the container, the PATH ENV var is set via the packer template

# allow all users to change what's in $GOPATH, as files are cached/installed
# there during a CI run
chmod -R a+w "${GOPATH}"
