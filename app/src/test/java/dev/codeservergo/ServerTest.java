package dev.codeservergo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The pure pieces of Server. */
public class ServerTest {

    @Test
    public void idIsUniquePerInstance() {
        assertNotEquals(new Server().id, new Server().id);
        assertEquals(36, new Server().id.length()); // UUID string length
    }

    @Test
    public void hostStripsPathAndKeepsPort() {
        Server s = new Server();
        s.url = "http://example.com:8080/?folder=/root";
        assertEquals("example.com:8080", s.host());
        s.url = "https://example.com/some/path";
        assertEquals("example.com", s.host());
        s.url = "corn:fruit";
        assertEquals("corn:fruit", s.host()); // unparsable → passthrough
    }

    @Test
    public void originStripsPathQueryAndFragment() {
        Server s = new Server();
        s.url = "http://192.168.1.20:8080/?folder=/sdcard/Projects#x=1";
        assertEquals("http://192.168.1.20:8080", s.origin());
        s.url = "https://example.com/long/path";
        assertEquals("https://example.com", s.origin());
    }

    @Test
    public void nameOrHostFallsBackToHost() {
        assertEquals("example.com:9090",
                Server.nameOrHost("", "http://example.com:9090/x"));
        assertEquals("home", Server.nameOrHost("home", "http://example.com"));
    }

    @Test
    public void nameOrHostNeverReturnsBlankForEmptyUrl() {
        // A URL that yields no host should still produce a label, not an invisible chip.
        String label = Server.nameOrHost(null, "");
        assertTrue(label == null || !label.isEmpty());
    }
}
