package com.robot.asus.kira;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class LanRemoteSecurityTest {
    @Test public void pairingIsSingleUseExpiringAndSingleController() {
        LanRemoteSecurity security = new LanRemoteSecurity(); security.enable(100);
        String code = security.pairingCode(100);
        assertTrue(code.matches("[0-9]{8}"));
        String token = security.pair(code,101);
        assertNotNull(token); assertNull(security.pairingCode(102));
        assertNull(security.pair(code,102));
        assertTrue(security.authenticated(token,103));
        assertFalse(security.canWrite(token,"incorrect",103));
        assertTrue(security.canWrite(token,security.csrfToken(),103));
        security.disable(); assertFalse(security.authenticated(token,104));
        security.enable(200); assertNull(security.pair(security.pairingCode(200),200 + LanRemoteSecurity.PAIR_TTL_MS));
    }
    @Test public void bruteForceIsThrottledAndCannotReviveAnExpiredLease() {
        LanRemoteSecurity security = new LanRemoteSecurity(); security.enable(100);
        String code = security.pairingCode(100);
        assertNull(security.pair("bad",101)); assertTrue(security.throttled(102));
        assertNull(security.pair(code,102));
        String token = security.pair(code,1101); assertNotNull(token);
        String csrf = security.csrfToken();
        assertTrue(security.heartbeat(token,csrf,1200));
        assertTrue(security.leaseAlive(1201));
        assertFalse(security.heartbeat(token,csrf,1200 + LanRemoteSecurity.HEARTBEAT_TIMEOUT_MS));
        assertTrue(security.expireLease(1200 + LanRemoteSecurity.HEARTBEAT_TIMEOUT_MS));
        assertFalse(security.authenticated(token,3000)); assertFalse(security.expireLease(3001));
    }
    @Test public void rebindingAndCrossOriginRequestsCannotReachLanApi() {
        java.util.List<String> hosts = Collections.singletonList("192.168.10.2:8788");
        assertTrue(LanRemoteSecurity.validRequest("192.168.10.3",hosts.get(0),"http://"+hosts.get(0),true,hosts));
        assertTrue(LanRemoteSecurity.validRequest("192.168.10.3",hosts.get(0),null,false,hosts));
        assertFalse(LanRemoteSecurity.validRequest("192.168.10.3",hosts.get(0),null,true,hosts));
        assertFalse(LanRemoteSecurity.validRequest("192.168.10.3","attacker.example:8788","http://attacker.example:8788",true,hosts));
        assertFalse(LanRemoteSecurity.validRequest("192.168.10.3",hosts.get(0),"https://attacker.example",false,hosts));
        assertFalse(LanRemoteSecurity.validRequest("8.8.8.8",hosts.get(0),null,false,hosts));
        assertFalse(LanRemoteSecurity.privateIpv4("192.168.001.1"));
        assertFalse(LanRemoteSecurity.privateIpv4("172.32.1.1"));
        assertFalse(LanRemoteSecurity.privateIpv4("192.168.1.999"));
    }
}
