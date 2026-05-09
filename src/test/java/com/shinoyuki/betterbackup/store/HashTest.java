package com.shinoyuki.betterbackup.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HashTest {

    @Test
    void equals_and_hashcode_match_array_contents() {
        Hash a = new Hash(new byte[]{0x12, 0x34, (byte) 0xAB, (byte) 0xCD});
        Hash b = new Hash(new byte[]{0x12, 0x34, (byte) 0xAB, (byte) 0xCD});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void different_bytes_yield_unequal_hashes() {
        Hash a = new Hash(new byte[]{0x01, 0x02});
        Hash b = new Hash(new byte[]{0x01, 0x03});
        assertNotEquals(a, b);
    }

    @Test
    void to_hex_is_lowercase_padded_full_length() {
        Hash h = new Hash(new byte[]{0x00, 0x12, (byte) 0xab, (byte) 0xff});
        assertEquals("0012abff", h.toHex());
    }

    @Test
    void from_hex_round_trips() {
        String hex = "deadbeef0012abff";
        Hash h = Hash.fromHex(hex);
        assertEquals(hex, h.toHex());
        assertEquals(8, h.length());
    }

    @Test
    void from_hex_rejects_odd_length() {
        assertThrows(IllegalArgumentException.class, () -> Hash.fromHex("abc"));
    }

    @Test
    void from_hex_rejects_invalid_chars() {
        assertThrows(IllegalArgumentException.class, () -> Hash.fromHex("zzzz"));
    }

    @Test
    void empty_bytes_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new Hash(new byte[0]));
    }

    @Test
    void bytes_getter_returns_clone_not_reference() {
        byte[] original = new byte[]{1, 2, 3};
        Hash h = new Hash(original);
        byte[] returned = h.bytes();
        returned[0] = 99;
        // 改返回的副本不影响内部状态
        assertArrayEquals(new byte[]{1, 2, 3}, h.bytes());
    }
}
