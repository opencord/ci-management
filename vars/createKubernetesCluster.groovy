// sets up a kubernetes cluster (using kind)

def call(Map config) {
    // note that I can't define this outside the function as there's no global scope in Groovy
    def defaultConfig = [
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
- role: control-plane
- role: worker
- role: worker
    """
    writeFile(file: 'kind.cfg', text: data)

    // TODO skip cluster creation if cluster is already there
    sh """
      mkdir -p $WORKSPACE/bin

      # download kind (should we add it to the base image?)
      curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.9.0/kind-linux-amd64
      chmod +x ./kind
      mv ./kind $WORKSPACE/bin/kind

      # install voltctl
      HOSTOS="\$(uname -s | tr "[:upper:]" "[:lower:"])"
      HOSTARCH="\$(uname -m | tr "[:upper:]" "[:lower:"])"
      if [ "\$HOSTARCH" == "x86_64" ]; then
          HOSTARCH="amd64"
      fi
      curl -Lo ./voltctl https://github.com/opencord/voltctl/releases/download/v1.3.1/voltctl-1.3.1-\$HOSTOS-\$HOSTARCH
      chmod +x ./voltctl
      mv ./voltctl $WORKSPACE/bin/

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

      # add helm repositories
      helm repo add onf https://charts.opencord.org
      helm repo update

      # download kail
      bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/bin"
  """
}
