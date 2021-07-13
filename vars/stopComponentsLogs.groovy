// stops all the kail processes created by startComponentsLog

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
        logsDir: "$WORKSPACE/logs",
        compress: false, // wether to compress the logs in a tgz file
    ]
    for(int i = 0;i<appsToLog.size();i++) {
        def label = appsToLog[i]
        def logFile = label.split('=')[1]
        def tag = "kail-${logFile}"
        println "Stopping logging process for label: ${label}"
        sh """
        P_IDS="$(ps e -ww -A | grep "_TAG=${tag}" | grep -v grep | awk '{print $1}')"
        if [ -n "$P_IDS" ]; then
            for P_ID in $P_IDS; do
                kill -9 $P_ID
            done
        fi
        """
        if (compress) {
            sh """
            tar czf ${logsDir}/combined.tgz *
            rm *.log
            """
        }
    }
}