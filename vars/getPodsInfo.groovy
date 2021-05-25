// This keyword will get all the kubernetes pods info needed for debugging
// the only parameter required is the destination folder to store the collected information
def call(String dest) {
  sh """
  mkdir -p ${dest}
  kubectl get pods --all-namespaces -o wide | tee ${dest}/pods.txt || true
  kubectl get svc --all-namespaces -o wide | tee ${dest}/svc.txt || true
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.image}{'\\n'}" | sort | uniq | tee ${dest}/pod-images.txt || true
  kubectl get pods --all-namespaces -o jsonpath="{range .items[*].status.containerStatuses[*]}{.imageID}{'\\n'}" | sort | uniq | tee ${dest}/pod-imagesId.txt || true
  kubectl describe pods --all-namespaces -l app.kubernetes.io/part-of=voltha > ${dest}/voltha-pods-describe.txt
  kubectl describe pods --all-namespaces -l app=onos-classic > ${dest}/onos-pods-describe.txt
  helm ls --all-namespaces | tee ${dest}/helm-charts.txt
  """
}
