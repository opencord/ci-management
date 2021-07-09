// returns the helm flags required to override a specific image
def call(String project = "unknown", String tag = "citest", String pullPolicy = "Never") {
  def chart = "unknown"
  def image = "unknown"
  switch(project) {
    case "ofagent-go":
      chart = "voltha"
      image = "ofagent"
    break
    case "voltha-go":
      chart = "voltha"
      image = "rw_core"
    break
    case "voltha-openonu-adapter-go":
      chart = "voltha-adapter-openonu"
      image = "adapter_open_onu_go"
    break
    // TODO end
    case "voltha-openolt-adapter":
      chart = "voltha-adapter-openolt"
      image = "adapter_open_olt"
    break
    case "bbsim":
      // BBSIM has a different format that voltha, return directly
      return "--set images.bbsim.tag=${tag},images.bbsim.pullPolicy=${pullPolicy},images.bbsim.registry='' "
    break
    case "voltha-onos":
      return "--set onos-classic.image.repository=voltha/voltha-onos,onos-classic.image.tag=citest,onos-classic.image.pullPolicy=${pullPolicy}"
    default:
      return ""
    break
  }

  return "--set ${chart}.images.${image}.tag=${tag},${chart}.images.${image}.pullPolicy=${pullPolicy},${chart}.images.${image}.registry=''  "
}
