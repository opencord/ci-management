
def call(Map config) {
    // note that I can't define this outside the function as there's no global scope in Groovy
    def defaultConfig = [
      bbsimReplica: 1,
      infraNamespace: "infra",
      volthaNamespace: "voltha",
      stackName: "voltha",
      stackId: 1, // NOTE this is used to differentiate between BBSims across multiple stacks
      workflow: "att",
      extraHelmFlags: "",
      localCharts: false,
    ]

    if (!config) {
        config = [:]
    }

    def cfg = defaultConfig + config

    def volthaStackChart = "onf/voltha-stack"

    if (cfg.localCharts) {
      volthaStackChart = "$WORKSPACE/voltha-helm-charts/voltha-stack"

      sh """
      pushd $WORKSPACE/voltha-helm-charts/voltha-stack
      helm dep update
      popd
      """
    }

    println "Deploying VOLTHA Stack with the following parameters: ${cfg}."

    sh """
    helm upgrade --install --create-namespace -n ${cfg.volthaNamespace} ${cfg.stackName} ${volthaStackChart} \
          --set global.stack_name=${cfg.stackName} \
          --set global.voltha_infra_name=voltha-infra \
          --set global.voltha_infra_namespace=${cfg.infraNamespace} \
          ${cfg.extraHelmFlags}
    """

    for(int i = 0;i<cfg.bbsimReplica;i++) {
      // NOTE we don't need to update the tag for DT
      script {
        if (cfg.workflow == "att" || cfg.workflow == "tt") {
          def startingStag = 900
          def bbsimCfg = readYaml file: "$WORKSPACE/voltha-helm-charts/examples/${cfg.workflow}-values.yaml"
          // NOTE we assume that the only service that needs a different s_tag is the first one in the list
          bbsimCfg["servicesConfig"]["services"][0]["s_tag"] = startingStag + i
          println "Using BBSim Service config ${bbsimCfg}"
          writeYaml file: "$WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml", data: bbsimCfg
        }
      }

      sh """
        helm upgrade --install --create-namespace -n ${cfg.volthaNamespace} bbsim${i} onf/bbsim \
        --set olt_id="${cfg.stackId}${i}" \
        -f $WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml \
        ${cfg.extraHelmFlags}
      """
    }

    println "Wait for VOLTHA Stack ${cfg.stackName} to start"

    sh """
        set +x
        voltha=\$(kubectl get pods -n ${cfg.volthaNamespace} -l app.kubernetes.io/part-of=voltha --no-headers | grep "0/" | wc -l)
        while [[ \$voltha != 0 ]]; do
          sleep 5
          voltha=\$(kubectl get pods -n ${cfg.volthaNamespace} -l app.kubernetes.io/part-of=voltha --no-headers | grep "0/" | wc -l)
        done
    """

    // also make sure that the ONOS config is loaded
    println "Wait for ONOS Config loader to complete"

    sh """
        set +x
        config=\$(kubectl get jobs.batch -n ${cfg.infraNamespace} --no-headers | grep "0/" | wc -l)
        while [[ \$config != 0 ]]; do
          sleep 5
          config=\$(kubectl get jobs.batch -n ${cfg.infraNamespace} --no-headers | grep "0/" | wc -l)
        done
    """
}
