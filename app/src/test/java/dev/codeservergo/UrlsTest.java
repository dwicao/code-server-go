package dev.codeservergo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlsTest {

    @Test
    public void normalizeAddsHttpSchemeForBareHost() {
        assertEquals("http://192.168.1.20:8080", Urls.normalize("192.168.1.20:8080"));
        assertEquals("http://localhost:8080", Urls.normalize("localhost:8080"));
    }

    @Test
    public void normalizeKeepsExplicitScheme() {
        assertEquals("https://example.com/", Urls.normalize("https://example.com/"));
        assertEquals("http://example.com/?folder=/root",
                Urls.normalize("http://example.com/?folder=/root"));
    }

    @Test
    public void normalizeRejectsNonHttpSchemes() {
        assertNull(Urls.normalize("ftp://example.com"));
        assertNull(Urls.normalize("ssh://example.com"));
        assertNull(Urls.normalize("javascript:alert(1)"));
    }

    @Test
    public void normalizeRejectsGarbage() {
        assertNull(Urls.normalize(""));
        assertNull(Urls.normalize("   "));
        assertNull(Urls.normalize(null));
        assertNull(Urls.normalize("http://"));
        assertNull(Urls.normalize("://nohost"));
    }

    @Test
    public void hostAndOriginStripPathQueryAndFragment() {
        assertEquals("example.com", Urls.host("https://example.com/?folder=/x#y"));
        assertEquals("example.com:9090", Urls.host("http://example.com:9090/abc"));
        assertEquals("http://example.com:9090", Urls.origin("http://example.com:9090/abc?x=1#frag"));
        assertEquals("https://example.com",
                Urls.origin("https://example.com/some/path?q=1"));
    }

    @Test
    public void nameOrHostFallsBackToHost() {
        assertEquals("home lab", Urls.nameOrHost("home lab", "http://example.com"));
        assertEquals("192.168.1.20:8080",
                Urls.nameOrHost("", "http://192.168.1.20:8080/?folder=/root"));
        assertEquals("192.168.1.20:8080",
                Urls.nameOrHost("   ", "http://192.168.1.20:8080"));
    }

    @Test
    public void originPreservesSchemePortAndDropsPath() {
        String url = Urls.normalize("192.168.1.20:8080/?folder=/sdcard/Projects");
        assertEquals("http://192.168.1.20:8080", Urls.origin(url));
        assertTrue(Urls.normalize(url).startsWith("http://"));
    }
}
