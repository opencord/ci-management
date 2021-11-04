def call(Map config) {

  def defaultConfig = [
      onosNamespace: "infra",
      apps: ['org.opencord.dhcpl2relay', 'org.opencord.olt', 'org.opencord.olt'],
      logLevel: "DEBUG",
  ]

  if (!config) {
      config = [:]
  }

  def cfg = defaultConfig + config

  def onosInstances = sh """
  kubectl get pods -n ${cfg.onosNamespace} -l app=onos-classic --no-headers | awk '{print $1}'
  """

  for(int i = 0;i<onosInstances.split( '\n' ).size();i++) {
    def instance = onosInstances.split('\n')[i]

      sh """
      _TAG="onos-pf" bash -c "while true; do kubectl port-forward -n ${cfg.onosNamespace} ${instance} 8101; done"&
      """

      for (int j = 0; j < apps.size(); j++) {
        def app = apps[i]
        sh """
        sshpass -p karaf ssh -q -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -p 8101 karaf@localhost log:set ${cfg.logLevel} ${app}
        """
      }

  })

  sh """
    set +x
    ps -ef | grep _TAG="onos-pf" | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9
  """
}
