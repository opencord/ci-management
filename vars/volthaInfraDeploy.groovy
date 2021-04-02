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

    sh """
    kubectl create namespace ${cfg.infraNamespace} || true
    kubectl create configmap -n ${cfg.infraNamespace} kube-config "--from-file=kube_config=$KUBECONFIG"  || true
    """
    // TODO support multiple replicas
    sh """
    helm upgrade --install --create-namespace -n ${cfg.infraNamespace} voltha-infra ${volthaInfraChart} ${cfg.extraHelmFlags} \
          --set onos-classic.replicas=${cfg.onosReplica},onos-classic.atomix.replicas=${cfg.atomixReplica} \
          -f $WORKSPACE/voltha-helm-charts/examples/${cfg.workflow}-values.yaml
    """
}
