# ci-management for CORD

This repo holds configuration for the Jenkins testing infrastructure used by
CORD.

The best way to work with this repo is to check it out with `repo`, per these
instructions: [Downloading testing and QA
repositories](https://guide.opencord.org/getting_the_code.html#downloading-testing-and-qa-repositories)

> NOTE: This repo uses git submodules. If you have trouble with the tests or
> other tasks, please run: `git submodule init && git submodule update` to
> obtain these submodules, as `repo` won't do this automatically for you.

## Jenkins Job Builder (JJB) Documentation

[Official JJB Docs](https://docs.openstack.org/infra/jenkins-job-builder/index.html)

[LF Best practices for
JJB](http://docs.releng.linuxfoundation.org/projects/global-jjb/en/latest/best-practices.html#)

### Testing job definitions

JJB job definitions can be tested by running:

```shell
make test
```

Which will create a python virtualenv, install jenkins-job-builder in it, then
try building all the job files.

### cord-infra macros

There are a few macros defined in jjb/cord-macros.yml

 - `cord-infra-gerrit-repo-scm` - checks out the entire source tree with the `repo` tool
 - `cord-infra-gerrit-repo-patch` - checks out a patch to a git repo within a checked out repo source tree
 - `cord-infra-gerrit-trigger` - triggers build on gerrit actions

## Things to look out for

### AMI Images and Cloud instances

If you make changes which create a new packer image, you have to manually set
the instance `AMI ID` on jenkins in [Global
Config](https://jenkins-new.opencord.org/configure) > Cloud > Amazon EC2.

### Creating new EC2 instance types

If you create a new cloud instance type, make sure to set both the `Security
group names` and `Subnet ID for VPC` or it will fail to instantiate.


## Links to other projects using LF JJB

 - [ONOS](https://gerrit.onosproject.org/gitweb?p=ci-management.git;a=tree)
 - [ODL](https://git.opendaylight.org/gerrit/gitweb?p=releng/builder.git;a=tree)
 - [ONAP](https://gerrit.onap.org/r/gitweb?p=ci-management.git;a=tree)

