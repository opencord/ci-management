
def call(Map config) {
    // note that I can't define this outside the function as there's no global scope in Groovy
    def defaultConfig = [
      bbsimReplica: 1,
      infraNamespace: "infra",
      volthaNamespace: "voltha",
      stackName: "voltha",
      stackId: 1, // NOTE this is used to differentiate between BBSims across multiple stacks
      workflow: "att",
      withMacLearning: false,
      extraHelmFlags: "",
      localCharts: false,
      onosReplica: 1,
      adaptersToWait: 2,
    ]

    if (!config) {
        config = [:]
    }

    def cfg = defaultConfig + config

    def volthaStackChart = "onf/voltha-stack"
    def bbsimChart = "onf/bbsim"

    if (cfg.localCharts) {
      volthaStackChart = "$WORKSPACE/voltha-helm-charts/voltha-stack"
      bbsimChart = "$WORKSPACE/voltha-helm-charts/bbsim"

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
          --set voltha.onos_classic.replicas=${cfg.onosReplica} \
          --set global.voltha_infra_namespace=${cfg.infraNamespace} \
          ${cfg.extraHelmFlags}
    """

    for(int i = 0;i<cfg.bbsimReplica;i++) {
      // NOTE we don't need to update the tag for DT
      script {
        sh """
        rm -f $WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml
        """
        if (cfg.workflow == "att" || cfg.workflow == "tt") {
          def startingStag = 900
          def serviceConfigFile = cfg.workflow
          if (cfg.withMacLearning && cfg.workflow == 'tt') {
            serviceConfigFile = "tt-maclearner"
          }
          def bbsimCfg = readYaml file: "$WORKSPACE/voltha-helm-charts/examples/${serviceConfigFile}-values.yaml"
          // NOTE we assume that the only service that needs a different s_tag is the first one in the list
          bbsimCfg["servicesConfig"]["services"][0]["s_tag"] = startingStag + i
          println "Using BBSim Service config ${bbsimCfg['servicesConfig']}"
          writeYaml file: "$WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml", data: bbsimCfg
        } else {
          // NOTE if it's DT just copy the file over
          sh """
          cp $WORKSPACE/voltha-helm-charts/examples/${cfg.workflow}-values.yaml $WORKSPACE/bbsimCfg${cfg.stackId}${i}.yaml
          """
        }
      }

      sh """
        helm upgrade --install --create-namespace -n ${cfg.volthaNamespace} bbsim${i} ${bbsimChart} \
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

    waitForAdapters(cfg)

    // also make sure that the ONOS config is loaded
    // NOTE that this is only required for VOLTHA-2.8
    println "Wait for ONOS Config loader to complete"

    // NOTE that this is only required for VOLTHA-2.8,
    sh """
        set +x
        config=\$(kubectl get jobs.batch -n ${cfg.infraNamespace} --no-headers | grep "0/" | wc -l)
        while [[ \$config != 0 ]]; do
          sleep 5
          config=\$(kubectl get jobs.batch -n ${cfg.infraNamespace} --no-headers | grep "0/" | wc -l)
        done
    """
    // NOTE that this is only required for VOLTHA-2.9 onwards, to wait until the pod completed,
    // meaning ONOS fully deployed
    sh """
        set +x
        config=\$(kubectl get pods -l app=onos-config-loader -n ${cfg.infraNamespace} --no-headers --field-selector=status.phase=Running | grep "0/" | wc -l)
        while [[ \$config != 0 ]]; do
          sleep 5
          config=\$(kubectl get pods -l app=onos-config-loader -n ${cfg.infraNamespace} --no-headers --field-selector=status.phase=Running | grep "0/" | wc -l)
        done
    """
}
