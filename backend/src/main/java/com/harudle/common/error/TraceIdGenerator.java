package com.harudle.common.error;

@FunctionalInterface
interface TraceIdGenerator {

    String generate();
}
