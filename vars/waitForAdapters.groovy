def call(Map config) {

    def defaultConfig = [
        volthaNamespace: "voltha",
        adaptersToWait: 2,
    ]

    if (!config) {
        config = [:]
    }

    def cfg = defaultConfig + config

    println "Wait for adapters to be registered"

    // guarantee that at least the specified number of adapters are registered with VOLTHA before proceeding
    sh """
        set +x
        _TAG="voltha-voltha-api" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${cfg.volthaNamespace} svc/voltha-voltha-api 55555:55555; done"&
        adapters=\$(voltctl adapter list -q | wc -l)
        while [[ \$adapters -lt ${cfg.adaptersToWait} ]]; do
          sleep 5
          adapters=\$(voltctl adapter list -q | wc -l)
        done
        ps aux | grep port-forw | grep -v grep | awk '{print \$2}' | xargs --no-run-if-empty kill -9 || true
    """
}
