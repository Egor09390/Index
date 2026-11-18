/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.config;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConfigTest {

    @Test(expected = BadConfigException.class)
    public void invalid_config_throws() throws IOException, BadConfigException {
        try (final InputStream is = Objects.requireNonNull(getClass().getClassLoader()).getResourceAsStream("broken.conf")) {
            Config.parse(is);
        }
    }

    @Test
    public void valid_config_parses_correctly() throws IOException, ParseException {
        Config config = null;
        final Collection<InetNetwork> expectedAllowedIps = new HashSet<>(Arrays.asList(InetNetwork.parse("0.0.0.0/0"), InetNetwork.parse("::0/0")));
        try (final InputStream is = Objects.requireNonNull(getClass().getClassLoader()).getResourceAsStream("working.conf")) {
            config = Config.parse(is);
        } catch (final BadConfigException e) {
            fail("'working.conf' should never fail to parse");
        }
        assertNotNull("config cannot be null after parsing", config);
        assertTrue(
                "No applications should be excluded by default",
                config.getInterface().getExcludedApplications().isEmpty()
        );
        assertEquals("Test config has exactly one peer", 1, config.getPeers().size());
        assertEquals("Test config's allowed IPs are 0.0.0.0/0 and ::0/0", config.getPeers().get(0).getAllowedIps(), expectedAllowedIps);
        assertEquals("Test config has one DNS server", 1, config.getInterface().getDnsServers().size());
    }

    @Test
    public void awg31_config_survives_export_and_reaches_userspace() throws IOException, BadConfigException {
        final String headerKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE=";
        final Config config = parseText(
                "[Interface]\n"
                        + "PrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=\n"
                        + "S1 = 12\nS2 = 12\nS3 = 12\nS4 = 12\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\n"
                        + "HeaderProtectionKey = " + headerKey + "\n"
                        + "ContentPaddingAddition = 2-4\nRekeyAfterTime = 100-110\nRekeyTimeout = 4-6\n"
                        + "RejectAfterTime = 170-180\nKeepaliveTimeout = 10-15\nMaxHandshakeAttempts = 15-20\n"
                        + "RandomTrailers = on\nDisableCookies = off\n"
                        + "[Peer]\n"
                        + "PublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=\n"
                        + "PersistentKeepalive = 25-35\n");

        final String exported = config.toAwgQuickString();
        final Config restored = parseText(exported);
        assertEquals(exported, restored.toAwgQuickString());
        assertEquals("25-35", restored.getPeers().get(0).getPersistentKeepalive().orElse(""));
        final String userspace = restored.toAwgUserspaceString();
        assertTrue(userspace.contains("header_protection_key="
                + config.getInterface().getHeaderProtectionKey().get().toHex() + "\n"));
        assertTrue(userspace.contains("content_padding_addition=2-4\n"));
        assertTrue(userspace.contains("rekey_after_time=100-110\n"));
        assertTrue(userspace.contains("rekey_timeout=4-6\n"));
        assertTrue(userspace.contains("reject_after_time=170-180\n"));
        assertTrue(userspace.contains("keepalive_timeout=10-15\n"));
        assertTrue(userspace.contains("max_handshake_attempts=15-20\n"));
        assertTrue(userspace.contains("random_trailers=1\n"));
        assertTrue(userspace.contains("disable_cookies=0\n"));
        assertTrue(userspace.contains("persistent_keepalive_interval=25-35\n"));
    }

    private static Config parseText(final String text) throws IOException, BadConfigException {
        return Config.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
}
