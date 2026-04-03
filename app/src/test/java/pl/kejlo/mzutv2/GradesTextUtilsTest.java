package pl.kejlo.mzutv2;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GradesTextUtilsTest {

    @Test
    public void cleanTrimsText() {
        assertEquals("Ocena", GradesTextUtils.clean("  Ocena  "));
    }

    @Test
    public void normalizeKeyRemovesDiacritics() {
        assertEquals("ocena koncowa", GradesTextUtils.normalizeKey(" Ocena Ko\u0144cowa "));
    }

    @Test
    public void extractBaseSubjectDropsTrailingType() {
        assertEquals("Matematyka", GradesTextUtils.extractBaseSubject("Matematyka (Wyk\u0142ad)"));
    }

    @Test
    public void extractTypeFromSubjectReadsTrailingType() {
        assertEquals("Wyk\u0142ad", GradesTextUtils.extractTypeFromSubject("Matematyka (Wyk\u0142ad)"));
    }

    @Test
    public void isFinalGradeLabelHandlesPlainText() {
        assertTrue(GradesTextUtils.isFinalGradeLabel("Ocena końcowa"));
    }
}
