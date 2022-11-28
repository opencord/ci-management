#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getIam(String func)
{
    String src = 'vars/waitForAdapters.groovy'
    String iam = [src, func].join('::')
    return iam
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
	adapters = 'SKIP' // why not just retry a few times (?)
    }

    print("** ${iam}: returned $adapters")
    return adapters
}

// -----------------------------------------------------------------------
// Intent: Interrogate adapter states to determine if we should continue
// looping waiting for adapter to start.  Passed values must all be
// Integral, Valid and within range to assert we are done waiting.
// Function will return incomplete at the first sign of an invalid value.
// -----------------------------------------------------------------------
// NOTE: list.find{} was replaced with a for loop to simplify
// early loop termination and return.
// -----------------------------------------------------------------------
// RETURN: scalar
//   'DOUBLE-DIGIT' - value exceeds 0-5sec response window.
//   'NON-NUMERIC'  - garbage in the stream (letter, symbol, ctrl)
//   'NULL'         - empty or null string detected
//   'VALID'        - succes, all adapters are functional
//   'SIX-NINE'     - detected a single numeric digit between 6 and 9.
// -----------------------------------------------------------------------
def getAdaptersState(String adapters0)
{
    String iam = getIam('getAdaptersState')
    Boolean debug = true // for now

    def adapters = adapters0.split('\n')

    String ans = null
    def found = []
    for(i=0; i<adapters.size(); i++)
    {
	String elapsed = adapters[i]
	if (debug)
	    println("** ${iam} Checking elapsed[$i]: $elapsed")

	if (! elapsed) // empty string or null
	{
	    ans = 'NULL'
	    break
	}

	Integer size = elapsed.length()
	if (size > 2) // 463765h58m52(s)
	{
	    // higlander: there can be only one
	    ans = 'DOUBLE-DIGIT'
	    break
	}

	if (elapsed.endsWith('s'))
	{
	    // safer than replaceAll('s'): ssss1s => 1
	    elapsed = elapsed.substring(0, size-1)
	}

	// Line noise guard: 'a', 'f', '#', '!'
	if (! elapsed.isInteger())
	{
	    ans = 'NON-NUMERIC'
	    break
	}

	// Is value in range:
	//   discard negative integers as just plain wonky.
	//   HMMM(?): zero/no latency response is kinda odd to include imho.
	Integer val = elapsed.toInteger()
	if (5 >= val && val >= 0)
	{
	    found.add(elapsed)
	}
	else
	{
	    ans = 'SIX-NINE'
	    break
	}
    } // for()

    // Declare success IFF all values are:
    //    o integral
    //    o valid
    //    o within range
    if (ans == null)
    {
	Integer got = found.size()
	Integer exp = adapters.size()
	ans = (! exp)      ? 'NO-ADAPTERS'
            : (got == exp) ? 'VALID'
            : 'CONTINUE'
    }

    if (debug)
	println("** ${iam} return: [$ans]")
    return ans
} // getAdaptersState

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
    Integer countdown = 60 * 10
    while (true)
    {
	sleep 1
	def adapters = getAdapters()
	// Why are we not failing hard on this condition ?
	// Adapters in an unknown state == testing: roll the dice
	if (adapters == 'SKIP') { break }

	def state = getAdaptersState(adapters)
	if (state == 'VALID') { break }   // IFF

	// ----------------------------------------------------------
	// Excessive timeout but unsure where startup time boundry is
	// [TODO] profile for a baseline
	// [TODO] groovy.transform.TimedInterrupt
	// ----------------------------------------------------------
	countdown -= 1
	if (1 > countdown)
	    throw new Exception("ERROR: Timed out waiting on adapter startup")
    }

    println("** ${iam}: Tearing down port forwarding")
    sh("""
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
