// stops all the kail processes created by startComponentsLog

def call(Map config) {

    def defaultConfig = [
        logsDir: "$WORKSPACE/logs",
        compress: false, // wether to compress the logs in a tgz file
    ]

    def tag = "jenkins-"
    println "Stopping all kail logging process"
    sh """
    P_IDS="\$(ps e -ww -A | grep "_TAG=jenkins-kail" | grep -v grep | awk '{print \$1}')"
    if [ -n "\$P_IDS" ]; then
        for P_ID in \$P_IDS; do
            kill -9 \$P_ID
        done
    fi
    """
    if (compress) {
        sh """
        tar czf ${cfg.logsDir}/combined.tgz *
        rm *.log
        """

    }
}