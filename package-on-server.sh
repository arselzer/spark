#!/bin/bash

HOST=as@10.3.0.4

rsync -av --exclude .git --exclude assembly --exclude build --exclude bin --exclude "*.jar" $PWD $HOST:spark
ssh $HOST "export JAVA_HOME=/usr/lib/jvm/default-java/"

#./build/sbt package
#cd python
#python3 setup.py sdist
#scp dist/pyspark-3.5.0.dev0.tar.gz as@10.3.0.4:
#cd ..
