#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getIam(String func)
{
    String src = 'vars/waitForAdapters.groovy'
    String iam = [src, func].join('::')
    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getAdapters()
{
    String iam = getIam('getAdapters')

    def adapters = ""
    try
    {
        adapters = sh (
            script: 'voltctl adapter list --format "{{gosince .LastCommunication}}"',
            returnStdout: true,
        ).trim()
    }
    catch (err)
    {
        // in some older versions of voltctl the command results in
        // ERROR: Unexpected error while attempting to format results
        // as table : template: output:1: function "gosince" not defined
        // if that's the case we won't be able to check the timestamp so
        // it's useless to wait
        println("voltctl can't parse LastCommunication, skip waiting")
	adapters = 'SKIP'
    }

    print("** ${iam}: returned $adapters")
    return adapters
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def process(Map config)
{
    String iam = getIam('process')
    println("** ${iam}: ENTER")

    def defaultConfig = [
        volthaNamespace: "voltha",
        stackName: "voltha",
        adaptersToWait: 2,
    ]

    def cfg = defaultConfig + config

    if (cfg.adaptersToWait == 0){
       //no need to wait
       println "No need to wait for adapters to be registered"
       return
    }

    println("** ${iam}: Wait for adapters to be registered")

    // guarantee that at least the specified number of adapters are registered with VOLTHA before proceeding
     sh """
        set +x
        _TAG="voltha-voltha-api" bash -c "while true; do kubectl port-forward --address 0.0.0.0 -n ${cfg.volthaNamespace} svc/${cfg.stackName}-voltha-api 55555:55555; done"&
       """

    sh """
        set +x
        adapters=\$(voltctl adapter list -q | wc -l)
        while [[ \$adapters -lt ${cfg.adaptersToWait} ]]; do
          sleep 5
          adapters=\$(voltctl adapter list -q | wc -l)
        done
    """

    // NOTE that we need to wait for LastCommunication to be equal or shorter that 5s
    // as that means the core can talk to the adapters
    // if voltctl can't read LastCommunication we skip this check

    println("** ${iam}: Wait for adapter LastCommunication")
    def done = false;
    while (!done)
    {
	sleep 1
	def adapters = getAdapters()
	if (adapters == 'SKIP') { break }

	def waitingOn = adapters.split( '\n' ).find{since ->
            since = since.replaceAll('s','') //remove seconds from the string

            // it has to be a single digit
            if (since.length() > 1) {
		return true
            }
            if ((since as Integer) > 5) {
		return true
            }
            return false
	}

	done = (waitingOn == null || waitingOn.length() == 0)
    }

    println("** ${iam}: Wait for adapter LastCommunication")
    sh("""
      set +x
      pgrep --list-full port-forw

      ps aux \
          | grep port-forw \
          | grep -v grep \
          | awk '{print \$2}' \
          | xargs --no-run-if-empty kill -9 || true
    """)

    println("** ${iam}: LEAVE")
    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(Map config)
{
    String iam = getIam('process')
    println("** ${iam}: ENTER")

    if (!config) {
        config = [:]
    }

    try
    {
	process(config)
    }
    catch (Exception err)
    {
	println("** ${iam}: EXCEPTION ${err}")
	throw err
    }
    finally
    {
	println("** ${iam}: LEAVE")
    }
    return
}

// EOF
