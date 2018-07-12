#!/usr/bin/env bash

# Copyright 2018-present Open Networking Foundation
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

# xos-unit.sh - perform a unit test on XOS or synchronizers
set -e -o pipefail

# when not running under Jenkins, use current dir as workspace, and 'xos' as GERRIT_PROJECT
WORKSPACE=${WORKSPACE:-.}
GERRIT_PROJECT=${GERRIT_PROJECT:-xos}

# find the path to the project that is checked out
PROJECT_PATH=$(xmllint --xpath "string(//project[@name=\"$GERRIT_PROJECT\"]/@path)" cord/.repo/manifest.xml)

cd "$WORKSPACE"

# create python virtual env
echo "Creating python virtualenv"
virtualenv -q venv-xos --no-site-packages
source venv-xos/bin/activate
pip install --upgrade pip setuptools

# Install XOS dependencies
pip install nose2 requests_mock plyxproto jinja2 pattern astunparse pyyaml colorama
pip install pykwalify==1.6.1
pip install multistructlog==1.5
pip install networkx==1.11
pip install netaddr==0.7.19
pip install ipaddress==1.0.19
pip install grpcio==1.9.1
pip install grpcio-tools==1.9.1
pip install kafka==1.3.5
echo "pip requirements Installed"

pushd "$WORKSPACE/cord/orchestration/xos/lib/xos-util"
python setup.py install
echo "xos-util Installed"
popd

pushd "$WORKSPACE/cord/orchestration/xos/lib/xos-config"
python setup.py install
echo "xos-config Installed"
popd

pushd "$WORKSPACE/cord/orchestration/xos/lib/xos-genx"
python setup.py install
echo "xos-genx Installed"
popd

pushd "$WORKSPACE/cord/orchestration/xos/xos/xos_client/xosapi"
ln -s ../../../../../component/chameleon chameleon
echo "chameleon Linked"
popd

pushd "$WORKSPACE/cord/orchestration/xos/xos/xos_client"
make
echo "xos-client Installed"
popd

if [ "$GERRIT_PROJECT" = 'xos' ] ; then
  pushd "$WORKSPACE/cord/$PROJECT_PATH"
else
  pushd "$WORKSPACE/cord/$PROJECT_PATH/xos"
fi

echo "Performing nose2 tests"
nose2 --verbose  --exclude-ignored-files --coverage-report xml --junit-xml
popd

