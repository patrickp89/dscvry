# dscvry
[![Build Status](https://github.com/patrickp89/dscvry/actions/workflows/build.yml/badge.svg)](https://github.com/patrickp89/dscvry/actions/workflows/build.yml)

A scalable CDDBd implementation.

## How to build it?
Install [SBT](https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Linux.html)
and build the project by simply running:
```bash
$ sbt compile
```

Create test coverage reports by running:
```bash
$ sbt coverage test coverageReport
```

Or via jacoco:
```bash
$ sbt jacoco
```