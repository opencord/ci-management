// This keyword will get all the kubernetes pods info needed for debugging
// the only parameter required is the destination folder to store the collected information
def call(String dest) {
  sh """
  mkdir -p ${dest}
  # only tee the main infos
  kubectl get pods --all-namespaces -o wide | tee ${dest}/pods.txt || true
  helm ls --all-namespaces | tee ${dest}/helm-charts.txt

  # everything else should not be dumped on the console
  kubectl get svc --all-namespaces -o wide > ${dest}/svc.txt || true
  kubectl get pvc --all-namespaces -o wide > ${dest}/pvcs.txt || true
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq > ${dest}/pod-images.txt || true
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq > ${dest}/pod-imagesId.txt || true
  kubectl describe pods --all-namespaces -l app.kubernetes.io/part-of=voltha > ${dest}/voltha-pods-describe.txt
  kubectl describe pods --all-namespaces -l app=onos-classic > ${dest}/onos-pods-describe.txt
  """
}
