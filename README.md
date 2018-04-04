# ci-management for CORD

This repo holds configuration for the Jenkins testing infrastructure used by
CORD.

The best way to work with this repo is to check it out with `repo`, per these
instructions: [Downloading testing and QA
repositories](https://guide.opencord.org/getting_the_code.html#downloading-testing-and-qa-repositories)

> NOTE: This repo uses git submodules. If you have trouble with the tests or
> other tasks, please run: `git submodule init && git submodule update` to
> obtain these submodules, as `repo` won't do this automatically for you.

## Testing job definitions

[Documentation for Jenkins Job Builder
(JJB)](https://docs.openstack.org/infra/jenkins-job-builder/index.html)

JJB job definitions can be tested by running:

```shell
make test
```

Which will create a python virtualenv, install jenkins-job-builder in it, then
try building all the job files.

