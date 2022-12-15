#!/usr/bin/env groovy
// -----------------------------------------------------------------------
// -----------------------------------------------------------------------

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def getIam(String func)
{
    // Cannot rely on a stack trace due to jenkins manipulation
    String src = 'vars/showCommands'
    String iam = [src, func].join('::')
    return iam
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def process(Map config)
{
    // String iam = getIam('process')

    // list.each{ } could be used here but simple for now.
    println("** ${iam}: voltctl command attributes")
    sh('''which -a voltctl''')
    sh('''voltctl version''')

    return
}

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
def call(Map config)
{
    String iam = getIam('main')
    println("** ${iam}: ENTER")

    if (!config)
    {
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
