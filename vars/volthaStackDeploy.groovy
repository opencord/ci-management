
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
      onosReplica: 1,
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
          def bbsimCfg = readYaml file: "$WORKSPACE/voltha-helm-charts/examples/${cfg.workflow}-values.yaml"
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

    println "Wait for adapters to be registered"

    // guarantee that at least two adapters are registered with VOLTHA before proceeding
    // this is potentially open to issue if we'll run test with multiple adapter pairs (eg: adtran + open)
    // untill then it is safe to assume we'll be ready once we have two adapters in the system
    sh """
      set +x
      _TAG="voltha-voltha-api" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${cfg.volthaNamespace} svc/voltha-voltha-api 55555:55555; done"&
    """
    sh """
        set +x
        adapters=\$(voltctl adapter list -q | wc -l)
        while [[ \$adapters -lt 2 ]]; do
          sleep 5
          adapters=\$(voltctl adapter list -q | wc -l)
        done
    """

    // NOTE that we need to wait for LastCommunication to be equal or shorter that 5s
    // as that means the core can talk to the adapters
    // if voltctl can read LastCommunication we skip this check
    def done = false;

    while (!done) {
      sleep 1
      def adapters = ""
      try {
        adapters = sh (
          script: 'voltctl adapter list --format "{{gosince .LastCommunication}}"',
          returnStdout: true,
        ).trim()
      } catch (err) {
        // in some older versions of voltctl the command results in
        // ERROR: Unexpected error while attempting to format results as table : template: output:1: function "gosince" not defined
        // if that's the case we won't be able to check the timestamp so it's useless to wait
        println("voltctl can't parse LastCommunication, skip waiting")
        done = true
        break
      }

      def waitingOn = adapters.split( '\n' ).find{since ->
        since = since.replaceAll('s','') //remove seconds from the string

        // it has to be a single digit
        if (since.length() > 1) {
            return true
        }
        if ((since as Integer) > 5) {
            return true
        }
        return false
      }
      done = (waitingOn == null || waitingOn.length() == 0)
    }

    sh """
      set +x
      ps aux | grep port-forw | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9 || true
    """

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
