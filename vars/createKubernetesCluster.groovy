// sets up a kubernetes cluster (using kind)

def call(Map config) {
    // note that I can't define this outside the function as there's no global scope in Groovy
    def defaultConfig = [
      branch: "master",
      nodes: 1,
      name: "kind-ci"
    ]

    if (!config) {
        config = [:]
    }

    def cfg = defaultConfig + config

    println "Deploying Kind cluster with the following parameters: ${cfg}."

    // TODO support different configs
    def data = """
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: worker
- role: worker
- role: control-plane
  kubeadmConfigPatches:
  - |
    kind: InitConfiguration
    nodeRegistration:
      kubeletExtraArgs:
        node-labels: "ingress-ready=true"
  extraPortMappings:
  - containerPort: 80
    hostPort: 80
    protocol: TCP
  - containerPort: 443
    hostPort: 443
    protocol: TCP
  - containerPort: 30115
    hostPort: 30115
  - containerPort: 30120
    hostPort: 30120
    """
    writeFile(file: 'kind.cfg', text: data)

    // TODO skip cluster creation if cluster is already there
    sh """
      mkdir -p $WORKSPACE/bin

      # download kind (should we add it to the base image?)
      curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.11.0/kind-linux-amd64
      chmod +x ./kind
      mv ./kind $WORKSPACE/bin/kind
    """
    // install voltctl
    installVoltctl("${cfg.branch}")
    sh """
      # start the kind cluster
      kind create cluster --name ${cfg.name} --config kind.cfg

      # remove NoSchedule taint from nodes
      for MNODE in \$(kubectl get node --selector='node-role.kubernetes.io/master' -o json | jq -r '.items[].metadata.name'); do
          kubectl taint node "\$MNODE" node-role.kubernetes.io/master:NoSchedule-
      done

      mkdir -p $HOME/.volt
      voltctl -s localhost:55555 config > $HOME/.volt/config

      mkdir -p $HOME/.kube
      kind get kubeconfig --name ${cfg.name} > $HOME/.kube/config
    """
    installKail
}
