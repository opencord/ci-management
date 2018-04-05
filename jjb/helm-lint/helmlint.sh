#/usr/bin/env bash

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

# helmlint.sh
# run `helm lint` on all helm charts that are found

set +e -u -o pipefail
echo "helmlint.sh, using helm version: $(helm version -c --short)"

fail_lint=0

for chart in $(find . -name Chart.yaml -print) ; do

  chartdir=$(dirname "${chart}")

  # lint with values.yaml if it exists
  if [ -f "${chartdir}/values.yaml" ]; then
    helm lint --strict --values "${chartdir}/values.yaml" "${chartdir}"
  else
    helm lint --strict "${chartdir}"
  fi

  rc=$?
  if [[ $rc != 0 ]]; then
    fail_lint=1
  fi
done

if [[ $fail_lint != 0 ]]; then
  exit 1
fi

exit 0
