package com.harudle.generation.diary.service.port;

/** 다른 실행이 잠금을 보유하면 기다리지 않고 false를 반환한다. */
@FunctionalInterface
public interface ImageBackupExecutionLock {
    boolean executeIfAvailable(Runnable operation);
}
