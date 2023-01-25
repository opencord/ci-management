# Common shell utilities

This subdirectory contains library shell scripts that support interrupt
handling and displaying a stack trace.

## Hierarchy may appear strange (common/common) but setup is intentional:

common/
├── common
│   └── sh
│       ├── stacktrace.sh
│       ├── tempdir.sh
│       └── traputils.sh
├── common.sh
└── preserve_argv.sh

### Usage: Source individual libraries by path

source common/common/sh/stacktrace.sh
source common/common/sh/tempdir.sh

### Usage: common.sh -- one-liner for sourcing sets of libraries.

source common/common.sh
source common/common.sh --tempdir
source common/common.sh --traputils --stacktrace


