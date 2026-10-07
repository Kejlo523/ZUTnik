package pl.kejlo.zutnik;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pl.kejlo.zutnik.PlanRepository.PlanDebug;
import pl.kejlo.zutnik.PlanRepository.PlanEventRaw;

/** Public timetable searches only; never starts the account's personal timetable sync. */
public final class UsosTimetableSearch {
    private static final long HOUR = 60L * 60L * 1000L;
    private static final long CATALOG_TTL = 7L * 24L * HOUR;
    private static final long BUILDINGS_TTL = 30L * 24L * HOUR;
    private static final long TIMETABLE_TTL = 6L * HOUR;
    private static final long FAILURE_BACKOFF = 5L * 60L * 1000L;
    private static final long MAX_BACKOFF = 6L * HOUR;
    private static final long REQUEST_SPACING_MS = 350L;
    private static final int MAX_ENTRIES = 192;
    private static final int MAX_RETRIES = 128;
    private static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    private static final int MAX_WEEK_REQUESTS = 6;
    private static final int MAX_COURSE_UNITS = 20;
    private static final String ACTIVITY_FIELDS = "type|start_time|end_time|name|course_id|course_name"
            + "|classtype_id|classtype_name|group_number|unit_id|building_name|room_number|frequency";
    private static final String COURSE_FIELDS = "id|name|terms";
    private static final String UNIT_FIELDS = "id|classtype_id|groups[group_number|course_unit_id"
            + "|class_type|course_name|term_id|lecturers]";
    private static final Pattern SELECTION = Pattern.compile("^(.*?)\\[([^\\[\\]]+)\\]\\s*$");
    private static final Pattern STAGE = Pattern.compile("^(.*?)\\[([^\\[\\]]+)\\]\\s*/\\s*(.*)$");
    private static final Object INSTANCE_LOCK = new Object();
    private static final Object FILE_LOCK = new Object();
    // Also serializes retired owners' in-flight requests during account changes.
    private static final ReentrantLock IO_LOCK = new ReentrantLock(true);
    private static long lastRequestStartedAt;
    @SuppressLint("StaticFieldLeak") // Application context only.
    private static volatile UsosTimetableSearch instance;

    public static final class Suggestion {
        public final String label;
        public final String query;
        public final boolean complete;

        public Suggestion(String label, String query, boolean complete) {
            this.label = label;
            this.query = query;
            this.complete = complete;
        }
    }

    public static final class Result {
        public final List<PlanEventRaw> events;
        /** Oldest verified component, or zero when any component is missing. */
        public final long timestamp;
        /** True for complete coverage returned entirely from previously verified data. */
        public final boolean cached;

        private Result(List<PlanEventRaw> events, long timestamp, boolean cached) {
            this.events = Collections.unmodifiableList(events);
            this.timestamp = timestamp;
            this.cached = cached;
        }
    }

    private final Context context;
    private final ZutnikSession capturedSession;
    private final String baseUrl;
    private final String userId;
    private final String studyId;
    private final String token;
    private final String secret;
    private final String owner;
    private final AtomicFile cacheFile;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Retry> retries = new LinkedHashMap<>();
    private volatile boolean retired;
    private boolean authFailed;
    private long requestsCompleted;

    public static UsosTimetableSearch get(Context context) {
        Context app = Objects.requireNonNull(context, "context").getApplicationContext();
        synchronized (INSTANCE_LOCK) {
            ZutnikSession session = ZutnikSession.getInstance(app);
            if (instance == null || !instance.matchesSession(session)) {
                if (instance != null) instance.retired = true;
                instance = new UsosTimetableSearch(app, session);
            }
            return instance;
        }
    }

    private UsosTimetableSearch(Context context, ZutnikSession session) {
        this.context = context;
        capturedSession = session;
        baseUrl = text(BuildConfig.USOS_BASE_URL);
        userId = text(session.getUserId());
        studyId = text(session.getActiveStudyId());
        token = text(session.getUsosAccessToken());
        secret = text(session.getUsosAccessTokenSecret());
        owner = identity(baseUrl, userId);
        File directory = new File(new File(context.getFilesDir(), "usos_search"), hash(owner));
        cacheFile = new AtomicFile(new File(directory, "search_v1.json"));
        readCache();
    }

    /** Synchronous, coalesced calls; the UI owns background execution and debounce. */
    public synchronized List<Suggestion> suggestions(String category, String query, LocalDate anchor)
            throws IOException, JSONException {
        checkCurrent();
        String kind = kind(category);
        String value = text(query);
        if (value.length() < 2) return Collections.emptyList();
        LocalDate date = anchor == null ? LocalDate.now() : anchor;
        Matcher stage = STAGE.matcher(value);
        List<Suggestion> result;
        if ("room".equals(kind)) {
            result = roomSuggestions(value, false);
        } else if ("group".equals(kind) && stage.matches()) {
            result = groupSuggestions(identifier(stage.group(2)), text(stage.group(1)),
                    text(stage.group(3)), date);
        } else {
            Matcher selected = SELECTION.matcher(value);
            if (selected.matches()) {
                target(kind, value);
                result = Collections.singletonList(new Suggestion(value, value, true));
            } else if (stage.matches()) {
                throw choose(kind);
            } else if ("teacher".equals(kind)) {
                Entry response = fetch("services/users/search2", params("lang", "pl", "query", value,
                        "among", "current_teachers", "num", "12", "fields",
                        "items[user[id|first_name|last_name]|match]|next_page"), CATALOG_TTL,
                        false, UsosTimetableSearch::validateTeachers, null);
                JSONArray items = response.object().getJSONArray("items");
                result = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject user = items.getJSONObject(i).getJSONObject("user");
                    String name = (scalar(user, "first_name") + " " + scalar(user, "last_name")).trim();
                    String canonical = canonical(name, numeric(scalar(user, "id")));
                    result.add(new Suggestion(canonical, canonical, true));
                }
            } else {
                Entry response = fetch("services/courses/search", params("lang", "pl", "name", value,
                        "num", "12", "fields", COURSE_FIELDS), CATALOG_TTL, false,
                        UsosTimetableSearch::validateCourseSearch, null);
                JSONArray items = response.object().getJSONArray("items");
                result = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject course = items.getJSONObject(i);
                    String id = identifier(scalar(course, "id"));
                    String name = language(course, "name");
                    putEntry(courseKey(id), new Entry(course.toString(), response.timestamp, CATALOG_TTL));
                    String canonical = canonical(name, id);
                    boolean complete = !"group".equals(kind);
                    result.add(new Suggestion(canonical, complete ? canonical : canonical + " / ", complete));
                }
                persist();
            }
        }
        checkCurrent();
        return Collections.unmodifiableList(result);
    }

    /** Inclusive dates. Missing cache is distinguishable from a verified empty timetable. */
    public synchronized Result load(String category, String query, LocalDate start, LocalDate end,
                                    boolean cacheOnly, PlanDebug debug) throws IOException, JSONException {
        checkCurrent();
        validateRange(start, end);
        long before = requestsCompleted;
        String kind = kind(category);
        String value = text(query);
        if (STAGE.matcher(value).matches()) throw choose(kind);
        if ("room".equals(kind) && !SELECTION.matcher(value).matches()) {
            List<Suggestion> rooms = roomSuggestions(value, cacheOnly);
            List<Suggestion> exact = new ArrayList<>();
            for (Suggestion room : rooms) {
                if (room.complete && exactRoom(value, room.label)) exact.add(room);
            }
            if (exact.size() != 1) throw choose(kind);
            value = exact.get(0).query;
        }
        Target target = target(kind, value);
        List<Entry> pieces = new ArrayList<>();
        List<PlanEventRaw> events = new ArrayList<>();
        if ("group".equals(kind)) {
            Entry activity = fetch("services/tt/classgroup_dates2", params("unit_id", target.id,
                    "group_number", target.group, "fields", ACTIVITY_FIELDS), TIMETABLE_TTL,
                    cacheOnly, UsosTimetableSearch::validateActivities, debug);
            pieces.add(activity);
            Entry metadata = entries.get(groupKey(target.id, target.group));
            if (activity != null) events.addAll(UsosTimetableMapper.parseActivities(activity.array(),
                    metadata == null ? null : metadata.object()));
        } else {
            List<Window> windows = windows(start, end);
            if ("course".equals(kind)) {
                Entry course = fetch("services/courses/course", params("course_id", target.id,
                        "fields", COURSE_FIELDS), CATALOG_TTL, cacheOnly,
                        UsosTimetableSearch::validateCourse, debug);
                pieces.add(course);
                if (course == null) return missing();
                List<Term> terms = terms(course, cacheOnly, debug, pieces);
                if (terms == null) return missing();
                List<Window> editions = new ArrayList<>();
                for (Window window : windows) {
                    for (Term term : terms) {
                        LocalDate from = later(window.start, term.start);
                        LocalDate to = earlier(window.end, term.finish);
                        if (!to.isBefore(from) && !to.isBefore(start) && !from.isAfter(end)) {
                            editions.add(new Window(from, to, term.id));
                        }
                    }
                }
                if (editions.size() > MAX_WEEK_REQUESTS) throw rangeTooWide();
                for (Window window : editions) {
                    Entry activity = fetch("services/tt/course_edition", params("course_id", target.id,
                            "term_id", window.term, "start", window.start.toString(),
                            "days", window.days(), "fields", ACTIVITY_FIELDS), TIMETABLE_TTL,
                            cacheOnly, UsosTimetableSearch::validateActivities, debug);
                    pieces.add(activity);
                    if (activity != null) events.addAll(UsosTimetableMapper.parseActivities(activity.array(), null));
                }
            } else {
                String endpoint = "teacher".equals(kind) ? "services/tt/staff" : "services/tt/room";
                String idField = "teacher".equals(kind) ? "user_id" : "room_id";
                for (Window window : windows) {
                    Entry activity = fetch(endpoint, params(idField, target.id, "start", window.start.toString(),
                            "days", "7", "fields", ACTIVITY_FIELDS), TIMETABLE_TTL,
                            cacheOnly, UsosTimetableSearch::validateActivities, debug);
                    pieces.add(activity);
                    if (activity != null) events.addAll(UsosTimetableMapper.parseActivities(activity.array(), null));
                }
                if ("teacher".equals(kind)) {
                    for (PlanEventRaw event : events) {
                        if (text(event.worker).isEmpty()) event.worker = target.label;
                    }
                }
            }
        }
        checkCurrent();
        Map<String, PlanEventRaw> unique = new LinkedHashMap<>();
        for (PlanEventRaw event : events) {
            LocalDate date = LocalDate.parse(event.start.substring(0, 10));
            if (!date.isBefore(start) && !date.isAfter(end)) unique.put(event.sourceId, event);
        }
        List<PlanEventRaw> filtered = new ArrayList<>(unique.values());
        filtered.sort(Comparator.comparing((PlanEventRaw event) -> event.start)
                .thenComparing(event -> event.sourceId));
        long timestamp = oldest(pieces);
        return new Result(filtered, timestamp, timestamp > 0L && before == requestsCompleted);
    }

    /** Network-free; zero also means incomplete coverage, not an empty successful response. */
    public synchronized long getCachedTimestamp(String category, String query, LocalDate start, LocalDate end) {
        try {
            return load(category, query, start, end, true, null).timestamp;
        } catch (IOException | JSONException | IllegalArgumentException ignored) {
            return 0L;
        }
    }

    private List<Suggestion> roomSuggestions(String query, boolean cacheOnly) throws IOException, JSONException {
        Matcher selected = SELECTION.matcher(query);
        if (selected.matches()) {
            target("room", query);
            return Collections.singletonList(new Suggestion(query, query, true));
        }
        Matcher stage = STAGE.matcher(query);
        if (stage.matches()) return rooms(identifier(stage.group(2)), text(stage.group(1)),
                text(stage.group(3)), cacheOnly);
        Entry index = fetch("services/geo/building_index", params("fields", "id|name"), BUILDINGS_TTL,
                cacheOnly, UsosTimetableSearch::validateBuildings, null);
        if (index == null) return Collections.emptyList();
        JSONArray buildings = index.array();
        int comma = query.lastIndexOf(',');
        if (comma > 0) {
            String buildingName = text(query.substring(0, comma));
            String roomNumber = text(query.substring(comma + 1));
            JSONObject exact = null;
            for (int i = 0; i < buildings.length(); i++) {
                JSONObject building = buildings.getJSONObject(i);
                if (same(buildingName, language(building, "name"))) {
                    if (exact != null) throw choose("room");
                    exact = building;
                }
            }
            if (exact != null) return rooms(identifier(scalar(exact, "id")),
                    language(exact, "name"), roomNumber, cacheOnly);
        }
        List<Suggestion> result = new ArrayList<>();
        boolean numericQuery = query.matches("\\d+");
        for (int i = 0; i < buildings.length(); i++) {
            JSONObject building = buildings.getJSONObject(i);
            String id = identifier(scalar(building, "id"));
            String name = language(building, "name");
            if (numericQuery || contains(name, query) || contains(id, query)) {
                String label = canonical(name, id);
                result.add(new Suggestion(label, label + " / ", false));
            }
        }
        result.sort(Comparator.comparing(suggestion -> suggestion.label));
        return result;
    }

    private List<Suggestion> rooms(String buildingId, String fallback, String filter, boolean cacheOnly)
            throws IOException, JSONException {
        Entry building = fetch("services/geo/building2", params("building_id", buildingId,
                "fields", "id|name|rooms[id|number]"), CATALOG_TTL, cacheOnly,
                UsosTimetableSearch::validateBuilding, null);
        if (building == null) return Collections.emptyList();
        JSONObject data = building.object();
        String name = language(data, "name");
        if (name.isEmpty()) name = fallback;
        JSONArray rooms = data.getJSONArray("rooms");
        List<Suggestion> result = new ArrayList<>();
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject room = rooms.getJSONObject(i);
            String number = scalar(room, "number");
            if (!contains(number, filter)) continue;
            String canonical = canonical(name + " / " + number, numeric(scalar(room, "id")));
            result.add(new Suggestion(canonical, canonical, true));
        }
        result.sort(Comparator.comparing(suggestion -> suggestion.label));
        return result;
    }

    private List<Suggestion> groupSuggestions(String courseId, String fallback, String filter, LocalDate anchor)
            throws IOException, JSONException {
        Entry course = fetch("services/courses/course", params("course_id", courseId, "fields", COURSE_FIELDS),
                CATALOG_TTL, false, UsosTimetableSearch::validateCourse, null);
        List<Term> terms = terms(course, false, null, new ArrayList<>());
        List<Suggestion> result = new ArrayList<>();
        int requestedUnits = 0;
        for (Term term : terms) {
            if (anchor.isBefore(term.start) || anchor.isAfter(term.finish)) continue;
            Entry edition = fetch("services/courses/course_edition", params("course_id", courseId,
                    "term_id", term.id, "fields", "course_units_ids"), CATALOG_TTL, false,
                    value -> object(value).getJSONArray("course_units_ids"), null);
            JSONArray ids = edition.object().getJSONArray("course_units_ids");
            Set<String> unitIds = new LinkedHashSet<>();
            for (int i = 0; i < ids.length(); i++) unitIds.add(numeric(scalar(ids.get(i))));
            if (unitIds.isEmpty()) continue;
            requestedUnits += unitIds.size();
            if (requestedUnits > MAX_COURSE_UNITS) throw new IOException(context.getString(R.string.usos_search_too_many_units));
            for (String unitId : unitIds) {
                // The batch units endpoint only supports primary fields; groups are secondary.
                Entry unitEntry = fetch("services/courses/unit", params("unit_id", unitId,
                        "fields", UNIT_FIELDS), CATALOG_TTL, false, UsosTimetableSearch::validateUnit, null);
                JSONObject unit = unitEntry.object();
                JSONArray groups = unit.getJSONArray("groups");
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject group = new JSONObject(groups.getJSONObject(i).toString());
                    String number = numeric(scalar(group, "group_number"));
                    group.put("course_unit_id", unitId);
                    group.put("course_id", courseId);
                    group.put("class_type_id", scalar(unit, "classtype_id"));
                    String name = language(group, "course_name");
                    if (name.isEmpty()) name = language(course.object(), "name");
                    if (name.isEmpty()) name = fallback;
                    String type = language(group, "class_type");
                    if (type.isEmpty()) type = scalar(unit, "classtype_id");
                    String label = name + " / " + type + " / gr." + number;
                    if (!contains(label, filter)) continue;
                    putEntry(groupKey(unitId, number), new Entry(group.toString(), unitEntry.timestamp, CATALOG_TTL));
                    String canonical = canonical(label, unitId + ":" + number);
                    result.add(new Suggestion(canonical, canonical, true));
                }
            }
        }
        persist();
        result.sort(Comparator.comparing(suggestion -> suggestion.label));
        return result;
    }

    private List<Term> terms(Entry course, boolean cacheOnly, PlanDebug debug, List<Entry> pieces)
            throws IOException, JSONException {
        JSONArray references = course.object().getJSONArray("terms");
        Set<String> ids = new java.util.TreeSet<>();
        for (int i = 0; i < references.length(); i++) {
            ids.add(identifier(scalar(references.getJSONObject(i), "id")));
        }
        if (ids.isEmpty()) return Collections.emptyList();
        if (ids.size() > 100) throw new IOException(context.getString(R.string.usos_search_too_many_terms));
        Entry response = fetch("services/terms/terms", params("term_ids", String.join("|", ids)),
                CATALOG_TTL, cacheOnly, value -> validateTerms(value, ids), debug);
        pieces.add(response);
        if (response == null) return null;
        List<Term> terms = new ArrayList<>();
        for (String id : ids) {
            JSONObject term = response.object().getJSONObject(id);
            terms.add(new Term(id, date(term, "start_date"), date(term, "finish_date")));
        }
        return terms;
    }

    private Entry fetch(String endpoint, Map<String, String> parameters, long ttl, boolean cacheOnly,
                        Validator validator, PlanDebug debug) throws IOException, JSONException {
        checkCurrent();
        String key = requestKey(endpoint, parameters);
        Entry old = entries.get(key);
        long now = System.currentTimeMillis();
        if (cacheOnly) return old;
        if (old != null && now >= old.timestamp && now - old.timestamp < ttl) return old;
        if (!NetworkStatusHelper.isNetworkAvailable(context)) {
            if (old != null) return old;
            throw new IOException(context.getString(R.string.usos_search_offline_uncached));
        }
        if (authFailed) throw new IOException(context.getString(R.string.usos_search_auth_required));
        if (backedOff(key, now) || backedOff("network", now)) {
            if (old != null) return old;
            throw new IOException(context.getString(R.string.usos_search_retry_later));
        }
        acquireIo();
        try {
            checkCurrent();
            pace();
            String body;
            try {
                // Values stay raw for OAuth signing; UsosApi owns URL escaping.
                body = UsosApi.getRaw(endpoint, token, secret, parameters);
                checkCurrent();
                JSONTokener parser = new JSONTokener(body);
                Object json = parser.nextValue();
                if (parser.nextClean() != 0) throw new JSONException("Trailing response data");
                validator.validate(json);
                if (body.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
                    throw new JSONException(context.getString(R.string.usos_search_invalid_response));
                }
            } catch (IOException failure) {
                checkCurrent();
                UsosApi.HttpException http = failure instanceof UsosApi.HttpException
                        ? (UsosApi.HttpException) failure : null;
                int code = http == null ? 0 : http.statusCode;
                if (code == 401) authFailed = true;
                recordFailure(key, http == null ? 0L : http.retryAfterMs);
                debug(debug, endpoint, parameters, code, false, 0);
                if (old != null && code != 401 && code != 403) return old;
                int message = code == 401 ? R.string.usos_search_auth_required
                        : code == 403 ? R.string.usos_search_permission_denied
                        : code == 429 ? R.string.usos_search_rate_limited : R.string.usos_search_service_unavailable;
                throw new IOException(context.getString(message));
            } catch (JSONException | IllegalArgumentException failure) {
                checkCurrent();
                recordFailure(key, 0L);
                debug(debug, endpoint, parameters, 200, false, 0);
                if (old != null) return old;
                throw new JSONException(context.getString(R.string.usos_search_invalid_response));
            }
            Entry entry = new Entry(body, System.currentTimeMillis(), ttl);
            checkCurrent();
            putEntry(key, entry);
            retries.remove(key);
            retries.remove("network");
            requestsCompleted++;
            persist();
            Object json = new JSONTokener(body).nextValue();
            debug(debug, endpoint, parameters, 200, true,
                    json instanceof JSONArray ? ((JSONArray) json).length() : ((JSONObject) json).length());
            return entry;
        } finally {
            IO_LOCK.unlock();
        }
    }

    private void recordFailure(String key, long retryAfter) {
        long now = System.currentTimeMillis();
        Retry previous = retries.get(key);
        int failures = previous == null ? 1 : Math.min(16, previous.failures + 1);
        long delay = Math.max(Math.min(MAX_BACKOFF, FAILURE_BACKOFF * (1L << (failures - 1))), retryAfter);
        long deadline = delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
        retries.put(key, new Retry(deadline, failures));
        retries.put("network", new Retry(deadline, failures));
        while (retries.size() > MAX_RETRIES) retries.remove(retries.keySet().iterator().next());
        persist();
    }

    private boolean backedOff(String key, long now) {
        Retry retry = retries.get(key);
        return retry != null && retry.nextAllowed > now;
    }

    private void putEntry(String key, Entry entry) {
        entries.remove(key);
        entries.put(key, entry);
        int bytes = 0;
        for (Entry value : entries.values()) bytes += value.json.getBytes(StandardCharsets.UTF_8).length;
        Iterator<Entry> iterator = entries.values().iterator();
        while ((entries.size() > MAX_ENTRIES || bytes > MAX_PAYLOAD_BYTES) && iterator.hasNext()) {
            bytes -= iterator.next().json.getBytes(StandardCharsets.UTF_8).length;
            iterator.remove();
        }
    }

    private void readCache() {
        try {
            byte[] bytes;
            synchronized (FILE_LOCK) {
                if (cacheFile.getBaseFile().length() > MAX_PAYLOAD_BYTES * 3L) return;
                bytes = cacheFile.readFully();
            }
            String encrypted = new String(bytes, StandardCharsets.UTF_8);
            if (!encrypted.startsWith("enc:v1:")) return;
            String plain = SecureLocalData.decrypt(context, encrypted);
            if (plain == null) return;
            JSONObject root = new JSONObject(plain);
            if (root.getInt("version") != 1 || !owner.equals(root.getString("owner"))) return;
            JSONObject records = root.getJSONObject("entries");
            Iterator<String> keys = records.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject record = records.getJSONObject(key);
                long timestamp = record.getLong("timestamp");
                long ttl = record.getLong("ttl");
                if (timestamp <= 0L || timestamp > System.currentTimeMillis() + FAILURE_BACKOFF
                        || (ttl != CATALOG_TTL && ttl != TIMETABLE_TTL && ttl != BUILDINGS_TTL)) continue;
                String json = record.getString("json");
                Object parsed = new JSONTokener(json).nextValue();
                if (!(parsed instanceof JSONArray) && !(parsed instanceof JSONObject)) continue;
                putEntry(key, new Entry(json, timestamp, ttl));
            }
            JSONObject attempts = root.getJSONObject("retries");
            keys = attempts.keys();
            while (keys.hasNext() && retries.size() < MAX_RETRIES) {
                String key = keys.next();
                JSONObject record = attempts.getJSONObject(key);
                retries.put(key, new Retry(record.getLong("next_allowed"),
                        Math.max(1, Math.min(16, record.getInt("failures")))));
            }
        } catch (IOException | JSONException | IllegalArgumentException ignored) {
            entries.clear();
            retries.clear();
        }
    }

    private void persist() {
        try {
            if (!isCurrent()) return;
            JSONObject root = new JSONObject().put("version", 1).put("owner", owner);
            JSONObject records = new JSONObject();
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                Entry entry = item.getValue();
                records.put(item.getKey(), new JSONObject().put("json", entry.json)
                        .put("timestamp", entry.timestamp).put("ttl", entry.ttl));
            }
            JSONObject attempts = new JSONObject();
            for (Map.Entry<String, Retry> item : retries.entrySet()) {
                attempts.put(item.getKey(), new JSONObject().put("next_allowed", item.getValue().nextAllowed)
                        .put("failures", item.getValue().failures));
            }
            root.put("entries", records).put("retries", attempts);
            String encrypted = SecureLocalData.encrypt(context, root.toString());
            if (encrypted == null) return;
            synchronized (FILE_LOCK) {
                if (!isCurrent()) return;
                File directory = cacheFile.getBaseFile().getParentFile();
                if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) return;
                FileOutputStream stream = null;
                try {
                    stream = cacheFile.startWrite();
                    stream.write(encrypted.getBytes(StandardCharsets.UTF_8));
                    if (!isCurrent()) return;
                    cacheFile.finishWrite(stream);
                    stream = null;
                } finally {
                    if (stream != null) cacheFile.failWrite(stream);
                }
            }
        } catch (IOException | JSONException ignored) {
            // Keep verified in-memory data; never fall back to plaintext persistence.
        }
    }

    private boolean matchesSession(ZutnikSession session) {
        return session != null && !retired && capturedSession == session && session.isUsosLogin()
                && baseUrl.equals(text(BuildConfig.USOS_BASE_URL)) && userId.equals(text(session.getUserId()))
                && studyId.equals(text(session.getActiveStudyId()))
                && token.equals(text(session.getUsosAccessToken()))
                && secret.equals(text(session.getUsosAccessTokenSecret()));
    }

    private boolean isCurrent() {
        return instance == this && matchesSession(ZutnikSession.getInstance());
    }

    private void checkCurrent() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException(context.getString(R.string.usos_search_cancelled));
        if (!isCurrent()) throw new IOException(context.getString(R.string.usos_search_session_changed));
        if (userId.isEmpty() || token.isEmpty() || secret.isEmpty()) {
            throw new IOException(context.getString(R.string.usos_search_auth_required));
        }
    }

    private void acquireIo() throws IOException {
        try {
            IO_LOCK.lockInterruptibly();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw new IOException(context.getString(R.string.usos_search_cancelled));
        }
    }

    private void pace() throws IOException {
        checkCurrent();
        long wait = lastRequestStartedAt == 0L ? 0L
                : REQUEST_SPACING_MS - (SystemClock.elapsedRealtime() - lastRequestStartedAt);
        if (wait > 0L) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                throw new IOException(context.getString(R.string.usos_search_cancelled));
            }
        }
        checkCurrent();
        lastRequestStartedAt = SystemClock.elapsedRealtime();
    }

    private void debug(PlanDebug debug, String endpoint, Map<String, String> parameters,
                       int code, boolean valid, int count) {
        if (debug == null || !isCurrent()) return;
        PlanDebug.RequestDebug request = new PlanDebug.RequestDebug();
        okhttp3.HttpUrl.Builder url = Objects.requireNonNull(okhttp3.HttpUrl.parse(baseUrl + endpoint)).newBuilder();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            url.addQueryParameter(parameter.getKey(), parameter.getValue());
        }
        request.url = url.build().toString();
        request.httpCode = code;
        request.jsonOk = valid;
        request.jsonCount = valid ? count : null;
        synchronized (debug) {
            debug.requests.add(request);
        }
    }

    private String kind(String category) throws IOException {
        String value = text(category).toLowerCase(Locale.ROOT);
        if (value.contains("teacher") || value.contains("wyk")) return "teacher";
        if (value.contains("room") || value.contains("sal")) return "room";
        if (value.contains("group") || value.contains("grup")) return "group";
        if (value.contains("subject") || value.contains("course") || value.contains("przedm")) return "course";
        if (value.contains("album") || value.contains("number") || value.contains("numer")) {
            throw new IOException(context.getString(R.string.usos_search_other_album_unsupported));
        }
        throw new IOException(context.getString(R.string.usos_search_unsupported_category));
    }

    private Target target(String kind, String query) throws IOException {
        Matcher match = SELECTION.matcher(query);
        if (!match.matches()) throw choose(kind);
        String label = text(match.group(1));
        String id = text(match.group(2));
        if ("group".equals(kind)) {
            String[] parts = id.split(":", -1);
            if (parts.length != 2) throw choose(kind);
            return new Target(numeric(parts[0]), numeric(parts[1]), label);
        }
        return new Target("course".equals(kind) ? identifier(id) : numeric(id), "", label);
    }

    private IOException choose(String kind) {
        return new IOException(context.getString(R.string.plan_search_choose_item));
    }

    private String identifier(String value) throws IOException {
        if (value.isEmpty() || value.length() > 200 || !value.matches("[^\\[\\]\\s|&?#:]+")) {
            throw new IOException(context.getString(R.string.usos_search_invalid_id));
        }
        return value;
    }

    private String numeric(String value) throws IOException {
        if (!value.matches("[0-9]{1,20}")) {
            throw new IOException(context.getString(R.string.usos_search_invalid_id));
        }
        return value;
    }

    private List<Window> windows(LocalDate start, LocalDate end) throws IOException {
        List<Window> result = new ArrayList<>();
        LocalDate monday = start.minusDays(start.getDayOfWeek().getValue() - 1L);
        for (LocalDate week = monday; !week.isAfter(end); week = week.plusWeeks(1)) {
            if (result.size() == MAX_WEEK_REQUESTS) throw rangeTooWide();
            result.add(new Window(week, week.plusDays(6), ""));
        }
        return result;
    }

    private IOException rangeTooWide() {
        return new IOException(context.getString(R.string.usos_search_range_too_wide));
    }

    private static void validateRange(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) throw new IllegalArgumentException("end before start");
    }

    private interface Validator {
        void validate(Object value) throws JSONException;
    }

    private static JSONObject object(Object value) throws JSONException {
        if (!(value instanceof JSONObject)) throw new JSONException("Expected an object");
        return (JSONObject) value;
    }

    private static JSONArray array(Object value) throws JSONException {
        if (!(value instanceof JSONArray)) throw new JSONException("Expected an array");
        return (JSONArray) value;
    }

    private static void required(JSONObject object, String field) throws JSONException {
        if (scalar(object, field).isEmpty()) throw new JSONException("Missing " + field);
    }

    private static void validateTeachers(Object value) throws JSONException {
        JSONArray items = object(value).getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject user = items.getJSONObject(i).getJSONObject("user");
            required(user, "id");
            required(user, "last_name");
        }
    }

    private static void validateCourse(Object value) throws JSONException {
        JSONObject course = object(value);
        required(course, "id");
        if (language(course, "name").isEmpty()) throw new JSONException("Missing name");
        JSONArray terms = course.getJSONArray("terms");
        for (int i = 0; i < terms.length(); i++) required(terms.getJSONObject(i), "id");
    }

    private static void validateCourseSearch(Object value) throws JSONException {
        JSONArray items = object(value).getJSONArray("items");
        for (int i = 0; i < items.length(); i++) validateCourse(items.getJSONObject(i));
    }

    private static void validateBuildings(Object value) throws JSONException {
        JSONArray buildings = array(value);
        for (int i = 0; i < buildings.length(); i++) {
            JSONObject building = buildings.getJSONObject(i);
            required(building, "id");
            if (language(building, "name").isEmpty()) throw new JSONException("Missing building name");
        }
    }

    private static void validateBuilding(Object value) throws JSONException {
        JSONObject building = object(value);
        required(building, "id");
        JSONArray rooms = building.getJSONArray("rooms");
        for (int i = 0; i < rooms.length(); i++) {
            required(rooms.getJSONObject(i), "id");
            required(rooms.getJSONObject(i), "number");
        }
    }

    private static void validateTerms(Object value, Set<String> ids) throws JSONException {
        JSONObject terms = object(value);
        for (String id : ids) {
            JSONObject term = terms.getJSONObject(id);
            if (date(term, "finish_date").isBefore(date(term, "start_date"))) {
                throw new JSONException("Invalid term dates");
            }
        }
    }

    private static void validateUnit(Object value) throws JSONException {
        JSONObject unit = object(value);
        required(unit, "id");
        JSONArray groups = unit.getJSONArray("groups");
        for (int i = 0; i < groups.length(); i++) required(groups.getJSONObject(i), "group_number");
    }

    private static void validateActivities(Object value) throws JSONException {
        JSONArray activities = array(value);
        for (int i = 0; i < activities.length(); i++) {
            JSONObject activity = activities.getJSONObject(i);
            required(activity, "type");
            try {
                String rawStart = activity.getString("start_time");
                String rawEnd = activity.getString("end_time");
                if (rawStart.length() != 19 || rawEnd.length() != 19) {
                    throw new JSONException("Invalid activity dates");
                }
                LocalDateTime start = LocalDateTime.parse(rawStart.replace(' ', 'T'));
                LocalDateTime end = LocalDateTime.parse(rawEnd.replace(' ', 'T'));
                if (!end.isAfter(start)) throw new JSONException("Invalid activity dates");
            } catch (DateTimeParseException failure) {
                throw new JSONException("Invalid activity dates");
            }
        }
    }

    private static LocalDate date(JSONObject object, String field) throws JSONException {
        try {
            return LocalDate.parse(object.getString(field));
        } catch (DateTimeParseException failure) {
            throw new JSONException("Invalid term date");
        }
    }

    private static long oldest(List<Entry> entries) {
        if (entries.isEmpty()) return 0L;
        long oldest = Long.MAX_VALUE;
        for (Entry entry : entries) {
            if (entry == null) return 0L;
            oldest = Math.min(oldest, entry.timestamp);
        }
        return oldest;
    }

    private static Result missing() {
        return new Result(Collections.emptyList(), 0L, false);
    }

    private static String courseKey(String id) {
        return requestKey("services/courses/course", params("course_id", id, "fields", COURSE_FIELDS));
    }

    private static String groupKey(String unit, String number) {
        return "group:" + identity(unit, number);
    }

    private static Map<String, String> params(String... pairs) {
        Map<String, String> result = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }

    private static String requestKey(String endpoint, Map<String, String> parameters) {
        StringBuilder result = new StringBuilder(identity(endpoint));
        for (Map.Entry<String, String> entry : new TreeMap<>(parameters).entrySet()) {
            result.append(identity(entry.getKey(), entry.getValue()));
        }
        return result.toString();
    }

    private static String identity(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) key.append(part.length()).append(':').append(part);
        return key.toString();
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(Character.forDigit((item >>> 4) & 15, 16));
                result.append(Character.forDigit(item & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String canonical(String label, String id) {
        return label + " [" + id + "]";
    }

    private static boolean exactRoom(String detail, String label) {
        Matcher selected = SELECTION.matcher(label);
        if (!selected.matches()) return false;
        int comma = detail.lastIndexOf(',');
        int slash = selected.group(1).lastIndexOf('/');
        return comma > 0 && slash > 0 && same(detail.substring(0, comma), selected.group(1).substring(0, slash))
                && same(detail.substring(comma + 1), selected.group(1).substring(slash + 1));
    }

    private static boolean same(String first, String second) {
        return text(first).equalsIgnoreCase(text(second));
    }

    private static boolean contains(String value, String query) {
        return value.toLowerCase(Locale.ROOT).contains(text(query).toLowerCase(Locale.ROOT));
    }

    private static String language(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof JSONObject) {
            JSONObject languages = (JSONObject) value;
            String polish = scalar(languages, "pl");
            return polish.isEmpty() ? scalar(languages, "en") : polish;
        }
        return scalar(value);
    }

    private static String scalar(JSONObject object, String field) {
        return scalar(object.opt(field));
    }

    private static String scalar(Object value) {
        return value instanceof String || value instanceof Number ? text(value.toString()) : "";
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static LocalDate earlier(LocalDate first, LocalDate second) {
        return first.isBefore(second) ? first : second;
    }

    private static LocalDate later(LocalDate first, LocalDate second) {
        return first.isAfter(second) ? first : second;
    }

    private static final class Target {
        final String id;
        final String group;
        final String label;

        Target(String id, String group, String label) {
            this.id = id;
            this.group = group;
            this.label = label;
        }
    }

    private static final class Entry {
        final String json;
        final long timestamp;
        final long ttl;

        Entry(String json, long timestamp, long ttl) {
            this.json = json;
            this.timestamp = timestamp;
            this.ttl = ttl;
        }

        JSONObject object() throws JSONException {
            return new JSONObject(json);
        }

        JSONArray array() throws JSONException {
            return new JSONArray(json);
        }
    }

    private static final class Retry {
        final long nextAllowed;
        final int failures;

        Retry(long nextAllowed, int failures) {
            this.nextAllowed = nextAllowed;
            this.failures = failures;
        }
    }

    private static final class Window {
        final LocalDate start;
        final LocalDate end;
        final String term;

        Window(LocalDate start, LocalDate end, String term) {
            this.start = start;
            this.end = end;
            this.term = term;
        }

        String days() {
            return Long.toString(java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1L);
        }
    }

    private static final class Term {
        final String id;
        final LocalDate start;
        final LocalDate finish;

        Term(String id, LocalDate start, LocalDate finish) {
            this.id = id;
            this.start = start;
            this.finish = finish;
        }
    }
}
