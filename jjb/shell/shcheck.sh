#!/usr/bin/env bash
# -----------------------------------------------------------------------
# Copyright 2017-2024 Open Networking Foundation Contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http:#www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# -----------------------------------------------------------------------
# SPDX-FileCopyrightText: 2017-2024 Open Networking Foundation Contributors
# SPDX-License-Identifier: Apache-2.0
# -----------------------------------------------------------------------
# Entropy: 0fcb5ffa-d1a4-11ee-be5e-9f44b7181764
# -----------------------------------------------------------------------
# Intent: Syntax check shell scripts with shellcheck
# -----------------------------------------------------------------------

set +e -u -o pipefail
fail_shellcheck=0

# verify that we have shellcheck-lint installed
command -v shellcheck  >/dev/null 2>&1 \
    || { echo "shellcheck not found, please install it" >&2; exit 1; }

# when not running under Jenkins, use current dir as workspace
WORKSPACE=${WORKSPACE:-.}

echo "=> Linting shell script with $(shellcheck --version)"

while IFS= read -r -d '' sf
do
  echo "==> CHECKING: ${sf}"
  shellcheck "${sf}"
  rc=$?
  if [[ $rc != 0 ]]; then
    echo "==> LINTING FAIL: ${sf}"
    fail_shellcheck=1
  fi
done < <(find "${WORKSPACE}" \( -name "*.sh" -o -name "*.bash" \) -print0)

exit ${fail_shellcheck}

# [EOF]
