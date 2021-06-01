// usage
//
// stage('test stage') {
//   steps {
//     volthaDeploy([
//       onosReplica: 3
//     ])
//   }
// }


def call(Map config) {
    // NOTE use params or directule extraHelmFlags??
    def defaultConfig = [
      onosReplica: 1,
      atomixReplica: 1,
      kafkaReplica: 1,
      etcdReplica: 1,
      infraNamespace: "infra",
      workflow: "att",
      extraHelmFlags: "",
      localCharts: false,
      kubeconfig: null, // location of the kubernetes config file, if null we assume it's stored in the $KUBECONFIG environment variable
    ]

    if (!config) {
        config = [:]
    }

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

    sh """
    kubectl create namespace ${cfg.infraNamespace} || true
    kubectl create configmap -n ${cfg.infraNamespace} kube-config "--from-file=kube_config=${kubeconfig}"  || true
    """

    // bitnamic/etch has change the replica format between the currently used 5.4.2 and the latest 6.2.5
    // for now put both values in the extra helm chart flags
    sh """
    helm upgrade --install --create-namespace -n ${cfg.infraNamespace} voltha-infra ${volthaInfraChart} \
          --set onos-classic.replicas=${cfg.onosReplica},onos-classic.atomix.replicas=${cfg.atomixReplica} \
          --set kafka.replicaCount=${cfg.kafkaReplica},kafka.zookeeper.replicaCount=${cfg.kafkaReplica} \
          --set etcd.statefulset.replicaCount=${cfg.etcdReplica} \
          --set etcd.replicaCount=${cfg.etcdReplica} \
          -f $WORKSPACE/voltha-helm-charts/examples/${cfg.workflow}-values.yaml ${cfg.extraHelmFlags}
    """
}
