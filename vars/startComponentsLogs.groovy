// check if kail is installed, if not installs it
// and then uses it to collect logs on specified containers

// appsToLog is a list of kubernetes labels used by kail to get the logs
// the generated log file is named with the string after =
// for example app=bbsim will generate a file called bbsim.log

// to archive the logs use: archiveArtifacts artifacts: '${logsDir}/*.log'
def call(Map config) {

    def defaultConfig = [
        appsToLog: [
            'app=onos-classic',
            'app=adapter-open-onu',
            'app=adapter-open-olt',
            'app=rw-core',
            'app=ofagent',
            'app=bbsim',
            'app=radius',
            'app=bbsim-sadis-server',
            'app=onos-config-loader',
        ],
        logsDir: "$WORKSPACE/logs"
    ]

    if (!config) {
        config = [:]
    }

    def cfg = defaultConfig + config

    // check if kail is installed and if not installs it
    sh """
    if ! command -v kail &> /dev/null
    then
        bash <( curl -sfL https://raw.githubusercontent.com/boz/kail/master/godownloader.sh) -b "$WORKSPACE/bin"
    fi
    """

    // fi the logsDir does not exists dir() will create it
    dir(cfg.logsDir) {
        for(int i = 0;i<cfg.appsToLog.size();i++) {
            def label = cfg.appsToLog[i]
            def logFile = label.split('=')[1]
            println "Starting logging process for label: ${label}"
            sh """
            _TAG=kail-${logFile} kail -l ${label} --since 1h > ${logsDir}/${logFile}.log&
            """
        }
    }
}