
def call(Map config) {
    // note that I can't define this outside the function as there's no global scope in Groovy
    def defaultConfig = [
      onosReplica: 1,
      atomixReplica: 1,
      kafkaReplica: 1,
      etcdReplica: 1,
      bbsimReplica: 1,
      infraNamespace: "infra",
      volthaNamespace: "voltha",
      stackName: "voltha",
      workflow: "att",
      extraHelmFlags: "",
    ]

    if (!config) {
        config = [:]
    }

    def cfg = defaultConfig + config

    println "Deploying VOLTHA Stack with the following parameters: ${cfg}."

    sh """
    helm upgrade --install --create-namespace -n ${cfg.volthaNamespace} ${cfg.stackName} onf/voltha-stack ${cfg.extraHelmFlags} \
          --set global.stack_name=${cfg.stackName} \
          --set global.voltha_infra_name=voltha-infra \
          --set global.voltha_infra_namespace=${cfg.infraNamespace} \
    """

    for(int i = 0;i<cfg.bbsimReplica;i++) {
      // TODO differentiate olt_id between different stacks
       sh """
         helm upgrade --install --create-namespace -n ${cfg.volthaNamespace} bbsim${i} onf/bbsim ${cfg.extraHelmFlags} \
         --set olt_id="1${i}" \
         -f $WORKSPACE/voltha-helm-charts/examples/${cfg.workflow}-values.yaml
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
