# ci-management for CORD

This repo holds configuration for the Jenkins testing infrastructure used by
CORD.

The best way to work with this repo is to check it out with `repo`, per these
instructions: [Downloading testing and QA
repositories](https://guide.opencord.org/developer/getting_the_code.html#testing-and-qa-repositories)

> NOTE: This repo uses git submodules. If you have trouble with the tests or
> other tasks, please run: `git submodule init && git submodule update` to
> obtain these submodules, as `repo` won't do this automatically for you.

## Jenkins Job Builder (JJB) Documentation

[Official JJB Docs](https://docs.openstack.org/infra/jenkins-job-builder/index.html)

[LF Best practices for
JJB](http://docs.releng.linuxfoundation.org/projects/global-jjb/en/latest/best-practices.html#)

[LF mailing list for release
engineering](https://lists.linuxfoundation.org/mailman/listinfo/lf-releng)

The `#lf-releng` channel on Freenode IRC is usually well attended.

## What should be in my job description?

When writing jobs, there are some things that JJB should be used to handle, and
some things that should be put in external scripts or pipeline jobs.

Some things that are good to put in a JJB job:

- Perform all SCM tasks (checkout code, etc.)
- Specify the executors type and size (don't hardcode this in a pipeline)

JJB Jobs should not:

- Have complicated (more than 2-5 lines) scripts inline - instead, include
  these using `!include-escape` or `!include-raw-escape`.

### Adding tests to a new git repo

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
   `dependency-jobs` variable to each item the `job-group` `jobs` list to
   control the order of jobs to invoke. Note that this is a string with the
   name of the jobs as created in Jenkins, not the `job-template` id.

### Making a new job-template

To create jobs that are usable by multiple repos, you want to create a
[job-template](https://docs.openstack.org/infra/jenkins-job-builder/definition.html#job-template)
that can be used by multiple jobs.

Most `job-template`s are kept in `jjb/*.yaml`. See `lint.yaml` or
`api-test.yaml` for examples.

Every `job-template` must have at least a `name` (which creates the name of the
job in Jenkins) and an `id` item (referred to in the `job-group`), as well as
several
[modules](https://docs.openstack.org/infra/jenkins-job-builder/definition.html#modules)
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
These are generally put in `jjb/cord-macros.yaml`, and have names matching
`cord-infra-*`.

See also `global-jjb/jjb/lf-macros.yaml` for more macros to use (these have
name matching `lf-infra-*`).

There are a few useful macros defined in `jjb/cord-macros.yml`

- `cord-infra-properties` - sets build discarder settings
- `cord-infra-gerrit-repo-scm` - checks out the entire source tree with the
  `repo` tool
- `cord-infra-gerrit-repo-patch` - checks out a patch to a git repo within a
  checked out repo source tree (WIP, doesn't work yet)
- `cord-infra-gerrit-trigger-patchset` - triggers build on gerrit new
  patchset, draft publishing, comments, etc.
- `cord-infra-gerrit-trigger-merge` - triggers build on gerrit merge

### Testing job definitions

JJB job definitions can be tested by running `make test`, which will create a
python virtualenv, install jenkins-job-builder in it, then try building all the
job files, which are put in `job-configs` and can be inspected.

The output of this is somewhat difficult to decipher, sometimes requiring you
to go through the python backtrace to figure out where the error occurred in
the jenkins-job-builder source code.

There is also a `make lint` target which will run `yamllint` on all the JJB
YAML files, which can catch a variety of formatting errors.

If you're writing a new shell script, it's a good idea to test it with
[shellcheck](https://github.com/koalaman/shellcheck) before including it -
failing to heed those messages then using `!include-escape` to add it to the
job may lead to hard to debug problems with the job definition.

## Using Pipeline jobs with JJB

Another way of creating jobs in Jenkins is to use the Pipeline method, which
traditionally is done by creating a Groovy script that describes a job. These
are traditionally stored in a "Jenkinsfile". It is recommended that you use the
[Declarative Pipeline](https://jenkins.io/doc/book/pipeline/syntax/) syntax,
which can be linted with the `shell/jjb/jflint.sh` script, which will verify
the pipeline syntax against the Jenkins server. This script may run
automatically on commits in the future, so please verfiy your scripts with it.

The recommended way of creating a pipeline job is to create a pipeline script
in `jjb/pipeline` with an extension of `.groovy`, and a `job-template` job that
calls it and uses the JJB `parameters` to configure the pipeline job.  One
necessary parameter is the `executorNode`, which should be defined in the job
or job template, but is used to specify the `agent` in the pipeline script (the
executor the job runs on).

For help writing pipeline jobs, please see the [Pipeline steps
documentation](https://jenkins.io/doc/pipeline/steps/) for help with the
syntax.

## Jenkins Executors and AMI Images

The Jenkins executors are spun up automatically in EC2, and torn down after
jobs have completed. Some are "one shot" and others (usually static or lint
checks) are re-used for run multiple jobs.

The AMI images used for these executors built with
[Packer](https://www.packer.io/) and most of the local configuration happens in
`packer/provision/basebuild.sh`. If you need a new tool installed in the
executor, you would add the steps to install it here.  It's verified, and when
merged generates a new AMI image.

> NOTE: Future builds won't automatically use the new AMI - you have to
> manually set the instance `AMI ID` on jenkins in [Global
> Config](https://jenkins.opencord.org/configure) > Cloud > Amazon EC2.
> The new AMI ID can be found near the end of the logs of the run of
> [ci-management-packer-merge-<ostype>-basebuild](https://jenkins.opencord.org/job/ci-management-packer-merge-ubuntu-16.04-basebuild/).

### Adding additional EC2 instance types

If you create a new cloud instance type, make sure to set both the `Security
group names` and `Subnet ID for VPC` or it will fail to instantiate.

## Links to other projects using LF JJB

- [ONOS](https://gerrit.onosproject.org/gitweb?p=ci-management.git;a=tree)
- [ODL](https://git.opendaylight.org/gerrit/gitweb?p=releng/builder.git;a=tree)
- [ONAP](https://gerrit.onap.org/r/gitweb?p=ci-management.git;a=tree)

