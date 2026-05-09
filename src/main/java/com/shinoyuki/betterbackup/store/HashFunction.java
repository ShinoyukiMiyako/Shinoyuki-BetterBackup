package com.shinoyuki.betterbackup.store;

/**
 * 抽象 hash 算法, 让 ConfigSpec.HashAlgorithm 在运行时切换 (XXH128 / SHA256 / BLAKE3)
 * 不需要散落 if-else.
 */
public interface HashFunction {

    Hash hash(byte[] input);

    /** 算法名 (用于诊断 log). */
    String name();

    /** 输出 hash 字节长度 (xxh128=16, sha256=32). */
    int outputLength();
}
