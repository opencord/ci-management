#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Copyright 2021-2024 Open Networking Foundation (ONF) and the ONF Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getIam(String func)
{
    String src = 'vars/getVolthaCode.groovy'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// Intent: Log progress message
// -----------------------------------------------------------------------
void enter(String name) {
    // Announce ourselves for log usability
    String iam = getIam(name)
    println("${iam}: ENTER")
    return
}

// -----------------------------------------------------------------------
// Intent: Log progress message
// -----------------------------------------------------------------------
void leave(String name) {
    // Announce ourselves for log usability
    String iam = getIam(name)
    println("${iam}: LEAVE")
    return
}

// TODO the 3 stages are very similar, most of the code can be shared

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def wrapped(Map config)
{
    def defaultConfig = [
        branch: "master",
        gerritProject: "",
        gerritRefspec: "",
        volthaSystemTestsChange: "",
        volthaHelmChartsChange: "",
    ]

    def cfg = defaultConfig + config

    println("""

** -----------------------------------------------------------------------
** Downloading VOLTHA code with the following parameters:
**   ${cfg}
** -----------------------------------------------------------------------
""")

    stage('Download Patch')
    {
        frequent_repos = [
            '',
            'voltha-system-tests',
            'voltha-helm-charts',
        ]

        // We are always downloading those repos, if the patch under test is in
        // one of those just checkout the patch, no need to clone it again
        if (cfg.gerritProject == '')
        {
            // Revisit:
            // gerritProject should be defined.  Ignore when empty was likely
            // added to support manually re-running a job when repo values
            // may not be defined.
            // Unfortunately the conditional can also inadvertently bypass
            // checkout during an error condition.
            // Case: when cfg= is invalid due to a jenkins hiccup.
        }
        else if (params.GERRIT_PROJECT == "onf-make")
        {
            // When testing onf-make, the tests are kicked off from the onf-make
            // repo, so the GERRIT_PROJECT and GERRIT_PATCHSET_REVISION params
            // will carry the data of what we want the submodule to be.
            // However, the gerritProject is overridden, so that we can pull
            // in another repo and run the tests to make sure that they work
            // with the code changes to onf-make.
            repo_project = "https://gerrit.opencord.org/${cfg.gerritProject}"

            checkout([
                $class: 'GitSCM',
                userRemoteConfigs: [[ url:repo_project ]],
                branches: [[ name: "${cfg.branch}", ]],
                extensions: [
                    [$class: 'WipeWorkspace'],
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: "${cfg.gerritProject}"],
                    [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
                    submodule(recursiveSubmodules: true, reference: "${params.GERRIT_PATCHSET_REVISION}"),
                ],
            ])

            sh("""pushd $WORKSPACE/${cfg.gerritProject}
                  git fetch "$repo_project" ${cfg.gerritRefspec} && git checkout FETCH_HEAD

                  echo "Currently on commit: \n"
                  git log -1 --oneline
                  popd
               """)
        }
        else if (!(cfg.gerritProject in frequent_repos))
        {
            repo_project = "https://gerrit.opencord.org/${cfg.gerritProject}"

            checkout([
                $class: 'GitSCM',
                userRemoteConfigs: [[ url:repo_project ]],
                branches: [[ name: "${cfg.branch}", ]],
                extensions: [
                    [$class: 'WipeWorkspace'],
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: "${cfg.gerritProject}"],
                    [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
                ],
            ])

            sh("""pushd $WORKSPACE/${cfg.gerritProject}
                  git fetch "$repo_project" ${cfg.gerritRefspec} && git checkout FETCH_HEAD

                  echo "Currently on commit: \n"
                  git log -1 --oneline
                  popd
               """)
        }
    }

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage('Clone voltha-system-tests')
    {
        enter("Clone voltha-system-tests @ BRANCH=[${cfg.branch}]")
        println("""

** -----------------------------------------------------------------------
** Clone voltha-system-tests
** -----------------------------------------------------------------------
""")
        repo_vst = 'https://gerrit.opencord.org/voltha-system-tests'

        checkout([
            $class: 'GitSCM',
            userRemoteConfigs: [[ url:repo_vst ]],
            branches: [[ name: "${cfg.branch}", ]],
            extensions: [
                [$class: 'WipeWorkspace'],
                [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-system-tests"],
                [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
            ],
        ])

        if (cfg.volthaSystemTestsChange != '' && cfg.gerritProject != 'voltha-system-tests')
            {
            enter("git fetch repo_vst=[${repo_vst}]")

            sh """
        cd "$WORKSPACE/voltha-system-tests"
        git fetch "${repo_vst}" ${cfg.volthaSystemTestsChange} && git checkout FETCH_HEAD
      """
            leave("git fetch repo_vst=[${repo_vst}]")
        }
        else if (cfg.gerritProject == 'voltha-system-tests') {
            enter("git fetch https://${cfg.gerritProject}")

            sh("""
        pushd "$WORKSPACE/${cfg.gerritProject}"
        git fetch https://gerrit.opencord.org/${cfg.gerritProject} ${cfg.gerritRefspec} && git checkout FETCH_HEAD

        echo "Currently on commit: \n"
        git log -1 --oneline
        popd
      """)
            leave("git fetch https://${cfg.gerritProject}")
        }

        leave("Clone voltha-system-tests @ BRANCH=[${cfg.branch}]")
    }

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    stage('Clone voltha-helm-charts')
    {
        enter("Clone voltha-helm-charts @ BRANCH=[${cfg.branch}]")
        repo_vhc = 'https://gerrit.opencord.org/voltha-helm-charts'

        checkout([
            $class: 'GitSCM',
            userRemoteConfigs: [[ url:repo_vhc ]],
            branches: [[ name: "${cfg.branch}", ]],
            extensions: [
                [$class: 'WipeWorkspace'],
                [$class: 'RelativeTargetDirectory', relativeTargetDir: "voltha-helm-charts"],
                [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: false],
            ],
        ])

        if (cfg.volthaHelmChartsChange != '' && cfg.gerritProject != 'voltha-helm-charts') {
            enter("git fetch repo_vhc=[$repo_vhc]")
            sh """
        cd "$WORKSPACE/voltha-helm-charts"
        git fetch "$repo_vhc" ${cfg.volthaHelmChartsChange} && git checkout FETCH_HEAD
      """
            leave("git fetch repo_vhc=[$repo_vhc]")
        }
        else if (cfg.gerritProject == 'voltha-helm-charts') {
            enter('cfg.gerritProject == voltha-helm-charts')
            sh """
        pushd "$WORKSPACE/${cfg.gerritProject}"
        git fetch "https://gerrit.opencord.org/${cfg.gerritProject}" ${cfg.gerritRefspec} && git checkout FETCH_HEAD

        echo "Currently on commit: \n"
        git log -1 --oneline
        popd
      """
            leave('cfg.gerritProject == voltha-helm-charts')
        }

        leave("Clone voltha-helm-charts @ BRANCH=[${cfg.branch}]")
    }
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(Map config)
{
    String iam = getIam('main')
    Boolean debug = true

    if (debug) {
        println("** ${iam}: ENTER")
    }

    config ?: [:]

    try
    {
        wrapped(config)
    }
    catch (Exception err)
    {
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
    finally
    {
        if (debug)
            {
            println("** ${iam}: LEAVE")
        }
    }

    return
}

// [EOF]
