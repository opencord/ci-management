#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// usage
//
// stage('test stage') {
//   steps {
//     volthaDeploy([
//       onosReplica: 3
//     ])
//   }
// }
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getIam(String func)
{
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/volthaInfraDeploy.groovy'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// Intent: Display and interact with kubernetes namespaces.
// -----------------------------------------------------------------------
def doKubeNamespaces()
{
    String iam = getIam('doKubeNamespaces')
    println("** ${iam}: ENTER")

    /*
     [joey] - should pre-existing hint the env is tainted (?)
     05:24:57  + kubectl create namespace infra
     05:24:57  Error from server (AlreadyExists): namespaces "infra" already exists
     05:24:57  error: failed to create configmap: configmaps "kube-config" already exists

     [joey] Thinking we should:
            o A special case exists  (create namespace)
            o helm upgrade --install (inital update)
     */

    namespaces = sh(
	script: 'kubectl get namespaces || true',
	returnStdout: true
    ).trim()
    print(namespaces)

    // Document prior to removal
    namespaces.each{namespace ->
	namespaces = sh("kubectl describe namespaces ${namespace} || true")
    }

    /*
     // [TODO] Remove if safe op: clean state and avoids a special case.
    namespaces.each{namespace ->
	namespaces = sh("kubectl delete namespaces ${namespace}")
    }
     */

    println("** ${iam}: LEAVE")    
    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def process(Map config)
{
    String iam = getIam('process')
    println("** ${iam}: ENTER")

    // NOTE use params or directule extraHelmFlags??
    def defaultConfig = [
      onosReplica: 1,
      atomixReplica: 1,
      kafkaReplica: 1,
      etcdReplica: 1,
      infraNamespace: "infra",
      workflow: "att",
      withMacLearning: false,
      withFttb: false,
      extraHelmFlags: "",
      localCharts: false,
      kubeconfig: null, // location of the kubernetes config file, if null we assume it's stored in the $KUBECONFIG environment variable
    ]

    def cfg = defaultConfig + config

    def volthaInfraChart = "onf/voltha-infra"

    if (cfg.localCharts) {
      volthaInfraChart = "$WORKSPACE/voltha-helm-charts/voltha-infra"

      sh """
      pushd $WORKSPACE/voltha-helm-charts/voltha-infra
      helm dep update
      popd
      """
    }

    println "Deploying VOLTHA Infra with the following parameters: ${cfg}."

    def kubeconfig = cfg.kubeconfig
    if (kubeconfig == null) {
      kubeconfig = env.KUBECONFIG
    }

    doKubeNamespaces() // WIP: joey

    sh """
    kubectl create namespace ${cfg.infraNamespace} || true
    kubectl create configmap -n ${cfg.infraNamespace} kube-config "--from-file=kube_config=${kubeconfig}"  || true
    """

    def serviceConfigFile = cfg.workflow
    if (cfg.withMacLearning && cfg.workflow == 'tt') {
      serviceConfigFile = "tt-maclearner"
    } else if (cfg.withFttb && cfg.workflow == 'dt') {
      serviceConfigFile = "dt-fttb"
    }

    // bitnamic/etch has change the replica format between the currently used 5.4.2 and the latest 6.2.5
    // for now put both values in the extra helm chart flags
    sh """
    helm upgrade --install --create-namespace -n ${cfg.infraNamespace} voltha-infra ${volthaInfraChart} \
          --set onos-classic.replicas=${cfg.onosReplica},onos-classic.atomix.replicas=${cfg.atomixReplica} \
          --set kafka.replicaCount=${cfg.kafkaReplica},kafka.zookeeper.replicaCount=${cfg.kafkaReplica} \
          --set etcd.statefulset.replicaCount=${cfg.etcdReplica} \
          --set etcd.replicaCount=${cfg.etcdReplica} \
          -f $WORKSPACE/voltha-helm-charts/examples/${serviceConfigFile}-values.yaml ${cfg.extraHelmFlags}
    """

    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(Map config)
{
    String iam = getIam('main')
    println("** ${iam}: ENTER")

    if (!config) {
        config = [:]
    }

    try
    {
	process(config)
    }
    catch (Exception err)
    {
	println("** ${iam}: EXCEPTION ${err}")
	throw err
    }
    finally
    {
	println("** ${iam}: LEAVE")
    }
    return
}

// [EOF]
