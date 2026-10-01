package pl.kejlo.zutnik;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Immutable render state: repository layout passes may mutate their UI models. */
final class PlanEventSnapshot {
    final List<Object> identity;
    private final List<Object> content;
    private final List<Object> bounds;

    PlanEventSnapshot(PlanRepository.PlanEventUi event) {
        if (event.isCustomEvent && nonempty(event.customEventId)) {
            identity = values("custom", event.customEventId);
        } else if (nonempty(event.sourceId)) {
            identity = values("source", event.sourceId);
        } else {
            identity = values("legacy", nonempty(event.subjectKey) ? event.subjectKey : event.title,
                    event.typeClass, event.group, event.startMin, event.endMin);
        }
        content = values(event.title, event.room, event.group, event.startStr, event.endStr,
                event.tooltip, event.typeClass, event.typeLabel, event.subjectKey, event.teacher,
                event.isCustomEvent, event.customEventType, event.hasCustomOverlay,
                event.customOverlayLabel, event.customEventId);
        bounds = values(event.startMin, event.endMin, event.leftPct, event.widthPct);
    }

    boolean sameContent(PlanEventSnapshot other) {
        return content.equals(other.content);
    }

    boolean sameBounds(PlanEventSnapshot other) {
        return bounds.equals(other.bounds);
    }

    private static List<Object> values(Object... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    private static boolean nonempty(String value) {
        return value != null && !value.isEmpty();
    }
}
