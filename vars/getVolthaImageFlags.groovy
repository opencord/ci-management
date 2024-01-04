#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Copyright 2021-2023 Open Networking Foundation (ONF) and the ONF Contributors
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

// returns the helm flags required to override a specific image
String call(String project = 'unknown', String tag = 'citest', String pullPolicy = 'Never') {
    String chart = 'unknown'
    String image = 'unknown'
    switch (project) {
        case 'ofagent-go':
            chart = 'voltha'
            image = 'ofagent'
            break
        case 'voltha-go':
            chart = 'voltha'
            image = 'rw_core'
            break
        case 'voltha-openonu-adapter-go':
            chart = 'voltha-adapter-openonu'
            image = 'adapter_open_onu_go'
            break
            // TODO end
        case 'voltha-openolt-adapter':
            chart = 'voltha-adapter-openolt'
            image = 'adapter_open_olt'
            break
        case 'bbsim':
            // BBSIM has a different format that voltha, return directly
            String ans = [
                "--set images.bbsim.tag=${tag}",
                "images.bbsim.pullPolicy=${pullPolicy}",
                "images.bbsim.registry=''",
            ].join(',')
            return(ans)
            break
        case 'voltha-onos':
            String ans = [
                '--set onos-classic.image.repository=voltha/voltha-onos',
                'onos-classic.image.tag=citest',
                "onos-classic.image.pullPolicy=${pullPolicy}",
            ].join(',')
            return (ans)
            break
        default:
            return ''
            break
    }

    String ans = [
        "--set ${chart}.images.${image}.tag=${tag}",
        "${chart}.images.${image}.pullPolicy=${pullPolicy}",
        "${chart}.images.${image}.registry=''  "
    ].join(',')

    println("getVolthaImageFlags return ${ans}")
    return(ans)
}

// [EOF]
