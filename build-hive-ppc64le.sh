#!/bin/bash
set -ex

HADOOP_VERSION=2.7.3.2.5.0.0-1245
#SPARK_VERSION=1.6.1
ZOOKEEPER_VERSION=3.4.6.2.5.0.0-1245
HBASE_VERSION=1.1.2.2.5.0.0-1245
TEZ_VERSION=0.7.0.2.5.0.0-1245

HIVE_MAVEN_OPTS=" -Dhbase.version=$HBASE_VERSION \
-Dzookeeper.version=$ZOOKEEPER_VERSION \
-Dhadoop.mr.rev=23 \
-Dhadoop.security.version=$HADOOP_VERSION \
-Dhadoop-23.version=$HADOOP_VERSION \
-Dhbase.hadoop2.version=$HBASE_VERSION \
-Dmvn.hadoop.profile=hadoop23 \
-DskipTests \
-Dhbase.version.with.hadoop.version=$HBASE_VERSION \
-Dtez.version=${TEZ_VERSION} 
"
#-Dspark.version=${SPARK_VERSION}


export MAVEN_OPTS="-Xmx1500m -Xms1500m -XX:MaxPermSize=256m"
mvn ${HIVE_MAVEN_OPTS} clean install -Phadoop-2,dist "$@"

#mkdir -p build/dist
#tar -C build/dist --strip-components=1 -xzf packaging/target/apache-hive-${HIVE_VERSION}-bin.tar.gz
