def call(String onosNameSpace = "infra", List apps = ['org.opencord.dhcpl2relay', 'org.opencord.olt', 'org.opencord.olt']) {
  def onosInstances = sh """
  kubectl get pods -n ${onosNameSpace} -l app=onos-classic --no-headers | awk '{print $1}'
  """

  println onosInstances
}
