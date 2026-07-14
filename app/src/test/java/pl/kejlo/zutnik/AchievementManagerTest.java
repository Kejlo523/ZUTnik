package pl.kejlo.zutnik;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class AchievementManagerTest {

    @Test
    public void ownAlbumSearch_requiresAlbumCategoryAndExactNormalizedNumber() {
        assertTrue(AchievementManager.isOwnAlbumSearch("album", " 57-796 ", "57796"));
        assertFalse(AchievementManager.isOwnAlbumSearch("teacher", "57796", "57796"));
        assertFalse(AchievementManager.isOwnAlbumSearch("album", "5779", "57796"));
        assertFalse(AchievementManager.isOwnAlbumSearch("album", "57796", null));
    }

    @Test
    public void normalization_isCaseInsensitiveAndDropsSeparators() {
        assertEquals("nj57796", AchievementManager.normalizeAlbumNumber(" NJ-57 796 "));
    }

    @Test
    public void achievementIds_areUnique() {
        Set<String> ids = new HashSet<>();
        for (AchievementManager.Achievement achievement : AchievementManager.Achievement.values()) {
            assertTrue(ids.add(achievement.id));
        }
        assertEquals(6, ids.size());
    }

    @Test
    public void nightPlanVisit_endsAtFiveInTheMorning() {
        assertTrue(AchievementManager.isNightPlanVisit(LocalTime.of(0, 0)));
        assertTrue(AchievementManager.isNightPlanVisit(LocalTime.of(4, 59)));
        assertFalse(AchievementManager.isNightPlanVisit(LocalTime.of(5, 0)));
        assertFalse(AchievementManager.isNightPlanVisit(LocalTime.of(23, 59)));
    }

    @Test
    public void fullView_requiresEveryPlanPerspective() {
        assertFalse(AchievementManager.hasViewedAllPlanModes(
                new HashSet<>(Arrays.asList("day", "week"))));
        assertTrue(AchievementManager.hasViewedAllPlanModes(
                new HashSet<>(Arrays.asList("month", "week", "day"))));
    }
}
