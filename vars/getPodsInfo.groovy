#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// Copyright 2021-2024 Open Networking Foundation Contributors
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
// This keyword will get all the kubernetes pods info needed for debugging
// the only parameter required is the destination folder to store the collected information
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
String getIam(String func) {
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/getPodsInfo.groovy'
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

// -----------------------------------------------------------------------
// Intent: Script workhorse
// -----------------------------------------------------------------------
// def call(String dest) {
Boolean process(String dest)
{
    // [TODO] post release remove '|| true'
    // Map cmds = [ label : { cmd, file } ]
    // cmds.for{ rec -> sh("cmd > file")

    sh("""
  mkdir -p ${dest}

  # only tee the main infos
  kubectl get pods --all-namespaces -o wide | tee ${dest}/pods.txt || true
  helm ls --all-namespaces | tee ${dest}/helm-charts.txt

  # everything else should not be dumped on the console
  kubectl get svc --all-namespaces -o wide > ${dest}/svc.txt || true
  kubectl get pvc --all-namespaces -o wide > ${dest}/pvcs.txt || true
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" \
    | sort \
    | uniq > ${dest}/pod-images.txt || true
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" \
    | sort \
    | uniq > ${dest}/pod-imagesId.txt || true
  kubectl describe pods --all-namespaces -l app.kubernetes.io/part-of=voltha > ${dest}/voltha-pods-describe.txt
  kubectl describe pods --all-namespaces -l app=onos-classic > ${dest}/onos-pods-describe.txt
""")

    return(true)
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
// def call(Map config=[:])
def call(String dest)
{
    Map config = [:] // Map config = config ?: [:]

    try
    {
        enter('main')
        process(dest)
    }
    catch (Exception err) // groovylint-disable-line CatchException
    {
        String iam = getIam('process')
        println("** ${iam}: EXCEPTION ${err}")
        throw err
    }
    finally
    {
        leave('main')
    }
    return
}

// [EOF]
