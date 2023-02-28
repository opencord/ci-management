# Construction zone

## Intent
Determine how to split voltha-nightly-jobs.yaml into standalone yaml config
files so functionality can be placed in named directories and files.  Currently
a good deal of searching and effort are needed to trace job construction.

```
% tree -n jjb/voltha-test/voltha-nightly-jobs
jjb/voltha-test/voltha-nightly-jobs
├── master.yaml
├── playground.yaml
├── voltha-2.11.yaml
└── voltha-2.8.yaml

0 directories, 4 files
```

## TODO

- Yaml does not appear to natively support includes or nested yaml files.
- Templates also contain inlined definitions such as (voltha-pipe-job-boiler-plate).
- Definitions are included within job templates by macro expasion

  - <<: *voltha-pipe-job-boiler-plate

- Definitions will also need to be isolated to allow including within a job template.



