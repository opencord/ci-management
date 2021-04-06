// this keyword is dedicated to deploy a single VOLTHA stack with infra
// If you need to deploy different configurations you can use the volthaInfraDeploy and volthaStackDeploy keywords

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
      stackId: 1,
      workflow: "att",
      extraHelmFlags: "",
      localCharts: false, // wether to use locally cloned charts or upstream one (for local we assume they are stored in $WORKSPACE/voltha-helm-charts)
      kubeconfig: null, // location of the kubernetes config file, if null we assume it's stored in the $KUBECONFIG environment variable
    ]

    if (!config) {
        config = [:]
    }

    def cfg = defaultConfig + config

    println "Deploying VOLTHA with the following parameters: ${cfg}."

    volthaInfraDeploy(cfg)

    volthaStackDeploy(cfg)
}
