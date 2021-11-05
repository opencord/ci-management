def call(Map config) {

  def defaultConfig = [
      onosNamespace: "infra",
      apps: ['org.opencord.dhcpl2relay', 'org.opencord.olt', 'org.opencord.aaa'],
      logLevel: "DEBUG",
  ]

  if (!config) {
      config = [:]
  }

  def cfg = defaultConfig + config

  def onosInstances = sh (
    script: "kubectl get pods -n ${cfg.onosNamespace} -l app=onos-classic --no-headers | awk '{print \$1}'",
    returnStdout: true
  ).trim()

  for(int i = 0;i<onosInstances.split( '\n' ).size();i++) {
    def instance = onosInstances.split('\n')[i]
      println "Setting log levels on ${instance}"
      sh """
      set +x
      _TAG="onos-pf" bash -c "while true; do kubectl port-forward -n ${cfg.onosNamespace} ${instance} 8101; done"&
      """

      for (int j = 0; j < cfg.apps.size(); j++) {
        def app = cfg.apps[j]
        sh """
        set +x
        sshpass -p karaf ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost log:set ${cfg.logLevel} ${app}
        """
      }
      sh """
        set +x
        ps e -ww -A | grep _TAG="onos-pf" | grep -v grep | awk '{print \$1}' | xargs --no-run-if-empty kill -9
      """
  }
}
