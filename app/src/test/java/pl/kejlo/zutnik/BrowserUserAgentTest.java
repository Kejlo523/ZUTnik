package pl.kejlo.zutnik;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BrowserUserAgentTest {

    @Test
    public void sameStudentSeedAlwaysProducesSameProfile() {
        String first = BrowserUserAgent.fromSeed("student:123456");
        String second = BrowserUserAgent.fromSeed("student:123456");

        assertEquals(first, second);
    }

    @Test
    public void profileLooksLikeMobileChromeWithoutExposingSeed() {
        String userAgent = BrowserUserAgent.fromSeed("student:987654");

        assertTrue(userAgent.startsWith("Mozilla/5.0 (Linux; Android "));
        assertTrue(userAgent.contains("AppleWebKit/537.36 (KHTML, like Gecko)"));
        assertTrue(userAgent.matches(".*Chrome/1(4[5-9]|50)\\.0\\.0\\.0 Mobile Safari/537\\.36$"));
        assertFalse(userAgent.contains("987654"));
        assertFalse(userAgent.contains("ZUTnik"));
    }
}
