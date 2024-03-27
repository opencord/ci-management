BATS unit testing
=================

check_repo_tags.bats
--------------------

- SRC: [jjb/pipeline/voltha/software-upgrades.groovy](https://gerrit.opencord.org/plugins/gitiles/ci-management/+/refs/heads/master/jjb/pipeline/voltha/software-upgrades.groovy#80)
- TST: [test/bats/check_repo_tags.bats](https://gerrit.opencord.org/plugins/gitiles/ci-management/+/refs/heads/master/test/bats/check_repo_tags.bats)
- LIB: [test/bats/utils/check_repo_tags.sh](https://gerrit.opencord.org/plugins/gitiles/ci-management/+/refs/heads/master/test/bats/utils/check_repo_tags.sh#31)

Unit test check_repo_tags.bats will exercise (git ls-remote) logic inlined within the software-upgrades.groovy script.  The test will gather a list of tagnames from the gerrit and github repositories then compare the lists for differences.


Interactive Testing
-------------------

check_repo_tags.bats contains two variables that can be uncommented to enable debugging.

- Uncomment to allow tagname differences to induce testing failures.

    - declare -g -i enable_fatal=1

- Test exclusions (skip) are supported but behavior is heavy handed.
  Skip will cause a test suite to prematurely exit on first failure
  VS iterate over all test conditions then ignore overall status.

    - declare -g -i enable_skip=1
    - For initial triage this test has been setup to call continue
      rather than skip.  Ignore individual test conditions that are
      a known failure point.
