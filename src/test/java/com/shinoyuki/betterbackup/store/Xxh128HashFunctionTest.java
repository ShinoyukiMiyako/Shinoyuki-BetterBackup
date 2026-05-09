package com.shinoyuki.betterbackup.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class Xxh128HashFunctionTest {

    @Test
    void same_input_yields_same_hash() {
        Xxh128HashFunction fn = new Xxh128HashFunction();
        byte[] input = "hello world".getBytes();
        Hash a = fn.hash(input);
        Hash b = fn.hash(input);
        assertEquals(a, b, "deterministic hash");
    }

    @Test
    void different_input_yields_different_hash() {
        Xxh128HashFunction fn = new Xxh128HashFunction();
        Hash a = fn.hash("aaa".getBytes());
        Hash b = fn.hash("aab".getBytes());
        assertNotEquals(a, b);
    }

    @Test
    void hash_length_is_16_bytes() {
        Xxh128HashFunction fn = new Xxh128HashFunction();
        Hash h = fn.hash("anything".getBytes());
        assertEquals(16, h.length());
        assertEquals(32, h.toHex().length());
    }

    @Test
    void empty_input_still_hashes() {
        Xxh128HashFunction fn = new Xxh128HashFunction();
        Hash h = fn.hash(new byte[0]);
        assertEquals(16, h.length());
    }

    @Test
    void large_input_does_not_throw() {
        Xxh128HashFunction fn = new Xxh128HashFunction();
        byte[] big = new byte[10 * 1024 * 1024]; // 10 MB
        Hash h = fn.hash(big);
        assertEquals(16, h.length());
    }

    @Test
    void instance_is_reentrant_across_threads() throws InterruptedException {
        Xxh128HashFunction fn = new Xxh128HashFunction();
        byte[] input = new byte[1024];
        // shared instance, multiple threads call hash() concurrently
        Hash[] results = new Hash[8];
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            int idx = i;
            threads[i] = new Thread(() -> results[idx] = fn.hash(input));
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        // 同一 input 跨线程 hash 结果必相等
        Hash first = results[0];
        for (Hash h : results) {
            assertEquals(first, h);
        }
    }
}
