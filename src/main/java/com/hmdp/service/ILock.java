package com.hmdp.service;

public interface ILock {
    boolean tryLock(long timeoutSec);

    void unlock();
}
