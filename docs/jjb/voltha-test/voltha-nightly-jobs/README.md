# Construction zone

## Intent
Determine how to split voltha-nightly-jobs.yaml into standalone yaml config
files so functionality can be placed in named directories and files.  Currently
a good deal of searching and effort are needed to trace job construction.

```
./jjb/voltha-test/voltha-nightly-jobs
├── master.yaml
├── playground.yaml
├── README.md
├── voltha-2.12.yaml
├── voltha-2.11.yaml
└── voltha-2.8.yaml

0 directories, 5 files
```

## TODO

- Yaml does not appear to natively support includes or nested yaml files.
- Templates also contain inlined definitions such as (voltha-pipe-job-boiler-plate).
- Definitions are included within job templates by macro expasion

  - <<: *voltha-pipe-job-boiler-plate

- Definitions will also need to be isolated to allow including within a job template.

## Structure

- To work around lack of include directives
- Coupled with a general inability to list a common pipeline name across distinct files.
- Single monolithic job configs are not going to scale well over time.
- Branch context can be introduced by naming conventions and subdirectories.
- A directory named for the pipeline-jobs.yaml file is created alongside the yaml.
- Create a yaml config within the subdir named for each release branch.
- A set of branch specific jobs accumulate in each of the release.yaml file.
- Only quark to all this is the job set must be unique across files (append release version).


## Nice to have

- A cleaner answer would be to create subdirectories named for distinct pipelines/jobs.
- Beneath the job subdir create individual release.yaml config files.
- voltha-nightly-jobs/voltha-2.12.yaml will eventually become monolithic as releases progress.
- Splitting configs based on job set would help reduce this config as well.

