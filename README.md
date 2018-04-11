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

### Adding a new git repo for testing

When adding a new git repo that needs tests:

1. Create a new file in `jjb/verify` named `<reponame>.yaml`

2. Create a
   [project](https://docs.openstack.org/infra/jenkins-job-builder/definition.html#project)
   using the name of the repo, and a
   [job-group](https://docs.openstack.org/infra/jenkins-job-builder/definition.html#job-group)
   section with a list of
   [jobs-template](https://docs.openstack.org/infra/jenkins-job-builder/definition.html#job-template)
   `id`s to invoke.

3. _Optional_: If you have more than one job that applies to the repo, add a
   `dependency-jobs` variable to each item the `job-group` `jobs` list to control the
   order of jobs to invoke. Note that this is a string with the name of the
   jobs as created in Jenkins, not the `job-template` id.

### Making a new jobs-template in JJB

To create jobs that are usable by multiple repos, you want to create a
[job-template](https://docs.openstack.org/infra/jenkins-job-builder/definition.html#job-template)
that can be used by multiple jobs.

Most `job-template`s are kept in `jjb/*.yaml`. See `lint.yaml` or
`api-test.yaml` for examples.

Every `job-template` must have at least a `name` (which creates the name of the
job in Jenkins) and an `id` item (referred to in the `job-group`), as well as
several
[modules]{https://docs.openstack.org/infra/jenkins-job-builder/definition.html#modules)
that invoke Jenkins functionality, or `macros` (see below, and in the docs)
that customize or provide defaults for those modules.

### Setting default variable values

Default values can be found in `jjb/defaults.yaml`.  These can be used in
`projects`, `jobs`, `job-templates`.

> NOTE: Defaults don't work with `macros` - [all
parameters must be passed to every macro
invocation](https://docs.openstack.org/infra/jenkins-job-builder/definition.html#macro-notes).

### Creating macros

If you need to customize how a Jenkins module is run, consider creating a
reusable
[macro](https://docs.openstack.org/infra/jenkins-job-builder/definition.html#macro).
These are generally put in `jjb/cord-macros.yaml`, and named `cord-infra-*`.

See also `global-jjb/jjb/lf-macros.yaml` for more macros to use (these
start with `lf-infra-*`).

There are a few useful macros defined in jjb/cord-macros.yml

- `cord-infra-properties` - sets build discarder settings
- `cord-infra-gerrit-repo-scm` - checks out the entire source tree with the
  `repo` tool
- `cord-infra-gerrit-repo-patch` - checks out a patch to a git repo within a
  checked out repo source tree (WIP, doesn't work yet)
- `cord-infra-gerrit-trigger-patchset` - triggers build on gerrit new
  patchset, draft publishing, comments, etc.
- `cord-infra-gerrit-trigger-merge` - triggers build on gerrit merge

### Testing job definitions

JJB job definitions can be tested by running:

```shell
make test
```

Which will create a python virtualenv, install jenkins-job-builder in it, then
try building all the job files, which are put in `job-configs` and can be
inspected.

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

