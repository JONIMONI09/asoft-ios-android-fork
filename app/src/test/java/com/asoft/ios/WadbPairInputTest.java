package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import org.junit.Test;

public class WadbPairInputTest {
    @Test
    public void parsesPlainSixDigitsWithKnownPort() {
        WadbPairService.ParsedInput r = WadbPairService.parsePairInput("123456", 37215);
        assertEquals("123456", r.code);
        assertEquals(37215, r.port);
    }

    @Test
    public void parsesSpacedThreeAndThreeDigitsWithKnownPort() {
        WadbPairService.ParsedInput r = WadbPairService.parsePairInput("123 456", 37215);
        assertEquals("123456", r.code);
        assertEquals(37215, r.port);
    }

    @Test
    public void parsesCodeAndExplicitPortSeparatedBySpaceOrColon() {
        WadbPairService.ParsedInput space = WadbPairService.parsePairInput("654321 41234", -1);
        assertEquals("654321", space.code);
        assertEquals(41234, space.port);

        WadbPairService.ParsedInput colon = WadbPairService.parsePairInput("654321:41234", -1);
        assertEquals("654321", colon.code);
        assertEquals(41234, colon.port);
    }

    @Test
    public void rejectsInvalidPortOutOfRange() {
        WadbPairService.ParsedInput r = WadbPairService.parsePairInput("654321 99999", -1);
        assertEquals("654321", r.code);
        assertEquals(-1, r.port);
    }

    @Test
    public void ownAddressMatchesLoopbackAndRawBytesAcrossIpv6Scopes() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertTrue(AdbMdns.ownAddress(loopback, Collections.emptyList()));

        byte[] fe80 = InetAddress.getByName("fe80::1").getAddress();
        InetAddress scoped = java.net.Inet6Address.getByAddress("wlan0", fe80, 5);
        InetAddress unscoped = InetAddress.getByAddress(fe80);
        assertTrue(AdbMdns.ownAddress(unscoped, Collections.singletonList(scoped)));

        InetAddress foreign = InetAddress.getByName("192.168.1.99");
        assertFalse(AdbMdns.ownAddress(foreign, Collections.singletonList(scoped)));
        // Sanity: null address never matches
        assertFalse(AdbMdns.ownAddress(null, Collections.singletonList(loopback)));
    }

    @Test
    public void gameAssistantKillDetectsZteAndOemGameTools() {
        assertTrue(CrashText.gameAssistantKill("FATAL in com.zte.gameassist"));
        assertTrue(CrashText.gameAssistantKill("com.nubia.gamelauncher hook"));
        assertFalse(CrashText.gameAssistantKill("normal SIGSEGV in qemu"));
        String words = CrashText.exitWords(CrashText.EXIT_CRASH_NATIVE, 11, "signal 11 in com.zte.gameassist");
        assertTrue(words.contains("Game Space"));
    }

    @Test
    public void portNotificationDeduplicatesDuplicatesAndTransientDrops() {
        assertTrue(WadbPairService.shouldNotifyPortChange(37215, -1));
        assertFalse(WadbPairService.shouldNotifyPortChange(37215, 37215));
        assertFalse(WadbPairService.shouldNotifyPortChange(-1, 37215));
        assertFalse(WadbPairService.shouldNotifyPortChange(0, 37215));
        assertTrue(WadbPairService.shouldNotifyPortChange(41999, 37215));
    }
}
