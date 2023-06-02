# Construction zone

## Intent
At release time branch test suites.  Creation is dependency driven so
existing test suites cannot be corrupted by stray typos.

## Help

```
% make help
Usage: make [options] [target] ...
  help-voltha-release Display voltha release targets

% make help-voltha-release
[RELEASE] - Create branch driven testing pipelines
  create-jobs-release
  create-jobs-release-nightly Nightly testing
  create-jobs-release-units   Unit testing
```

## Usage
- git clone ci-management
- make voltha-version=voltha-2.12 create-jobs-release

## Clean targets (dangerous)
- make voltha-version=voltha-2.12 sterile-create-jobs-release

## See Also
- docs/jjb/voltha-test/voltha-nightly-jobs/README.md
