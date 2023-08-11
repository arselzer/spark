#!/bin/bash

set -e

export JAVA_HOME=/usr/lib/jvm/default-java/

./build/sbt package
cd python
python3 setup.py sdist
scp dist/pyspark-3.5.0.dev0.tar.gz as@10.3.0.4:
cd ..
ssh as@10.3.0.4 "pip3 install pyspark-3.5.0.dev0.tar.gz"
