#!/usr/bin/env bash

cd $(dirname $0)
cd ../../

JAR_FILE=$(ls target/reactor-aeron-benchmarks*.jar |grep jar)

java \
    -cp ${JAR_FILE}:target/lib/* \
    -XX:+UnlockDiagnosticVMOptions \
    -XX:GuaranteedSafepointInterval=300000 \
    ${JVM_OPTS} reactor.aeron.netty.ReactorNettyServerPong
