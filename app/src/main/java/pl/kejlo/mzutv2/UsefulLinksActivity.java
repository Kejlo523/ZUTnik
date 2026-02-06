package pl.kejlo.mzutv2;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Useful links" screen.
 *
 * Assumptions:
 * - Shows selected, most frequently used links (global + faculty + major).
 * - Links matched to the user's majors / faculties go to the top,
 * remaining links are below.
 * - No spinners / selectors – priority is based on data from MzutSession.
 *
 * Expected layout: res/layout/activity_useful_links.xml
 * - DrawerLayout @+id/drawerLayout
 * - NavigationView @+id/navigationView
 * - Toolbar @+id/toolbar
 * - RecyclerView @+id/listLinks
 * - TextView @+id/tvLinksEmpty (optional "no data" label)
 */
public class UsefulLinksActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;
    private LinearLayout drawerContentRoot;

    private RecyclerView listLinks;
    private TextView tvEmpty;

    private LinksAdapter adapter;
    private final List<LinkItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        ThemeManager.applySystemBars(this);
        setContentView(R.layout.activity_useful_links);
        ThemeManager.applySystemBars(this);

        drawerContentRoot = findViewById(R.id.drawerContentRoot);
        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);
        listLinks = findViewById(R.id.listLinks);
        tvEmpty = findViewById(R.id.tvLinksEmpty);

        ViewCompat.setOnApplyWindowInsetsListener(drawerContentRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        if (toolbar != null) {
            toolbar.setTitle(R.string.nav_useful_links);
        }

        // NavDrawer – use "links" menu item
        NavDrawerHelper.setupNavigation(this, drawerLayout, navigationView, toolbar, "links");

        listLinks.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LinksAdapter(items);
        listLinks.setAdapter(adapter);

        loadAndSortLinksForUser();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ThemeManager.applySystemBars(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ThemeManager.applySystemBars(this);
        }
    }

    // Main logic

    private void loadAndSortLinksForUser() {
        // 1) Build full (but already trimmed) list of links
        List<LinkItem> all = buildAllLinks();

        if (all.isEmpty()) {
            if (tvEmpty != null) {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText(R.string.useful_links_empty);
            }
            return;
        }

        // 2) Read user's majors and faculty codes
        Set<String> majorCodesOfUser = new HashSet<>();
        Set<String> facultyCodesOfUser = new HashSet<>();
        detectUserCodes(majorCodesOfUser, facultyCodesOfUser);

        // 3) Compute "weight" of each link for this user
        for (LinkItem li : all) {
            li.priorityWeight = computeWeightForUser(li, majorCodesOfUser, facultyCodesOfUser);
        }

        // 4) Sort: first links matching the user, then global, then the rest
        Collections.sort(all, new Comparator<LinkItem>() {
            @Override
            public int compare(LinkItem a, LinkItem b) {
                int w = Integer.compare(a.priorityWeight, b.priorityWeight);
                if (w != 0) {
                    return w;
                }
                return a.title.compareToIgnoreCase(b.title);
            }
        });

        items.clear();
        items.addAll(all);
        adapter.notifyDataSetChanged();

        if (tvEmpty != null) {
            tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Detects, based on current studies:
     * - major codes (INF, EKO, ...),
     * - faculty codes (WI, WNEIZ, ...).
     */
    private void detectUserCodes(Set<String> majors, Set<String> faculties) {
        MzutSession s = MzutSession.getInstance();
        List<Study> studies = s.getStudies();

        if (studies == null) {
            return;
        }

        for (Study st : studies) {
            if (st == null) {
                continue;
            }
            String label = st.toString();
            if (label == null) {
                continue;
            }
            String l = label.toLowerCase(Locale.ROOT);

            // Computer Science (WI)
            if (l.contains("informatyka")) {
                majors.add("INF");
                faculties.add("WI");
            }

            // Economics (WNEIZ)
            if (l.contains("ekonomia")) {
                majors.add("EKO");
                faculties.add("WNEIZ");
            }

            // Mechanical Engineering (WIMiM)
            if (l.contains("mechanika") || l.contains("budowa maszyn")) {
                majors.add("MIB");
                faculties.add("WIMIM");
            }

            // Electrical Engineering (WE)
            if (l.contains("elektrotechnika") || l.contains("automatyka")) {
                majors.add("ELE");
                faculties.add("WE");
            }

            // Architecture and Civil Engineering (WBiA)
            if (l.contains("budownictwo") || l.contains("architektura")) {
                majors.add("BUD");
                faculties.add("WBIA");
            }
        }
    }

    /**
     * Link weight – lower value means higher position in the list.
     * 0 – matches user's MAJOR,
     * 1 – matches user's FACULTY,
     * 2 – GLOBAL,
     * 3 – everything else.
     */
    private int computeWeightForUser(LinkItem li,
            Set<String> majors,
            Set<String> faculties) {

        if (li.scope == LinkScope.MAJOR && li.majorCode != null &&
                majors.contains(li.majorCode)) {
            li.highlight = true;
            return 0;
        }

        if (li.scope == LinkScope.FACULTY && li.facultyCode != null &&
                faculties.contains(li.facultyCode)) {
            li.highlight = true;
            return 1;
        }

        if (li.scope == LinkScope.GLOBAL) {
            return 2;
        }

        return 3;
    }

    /** Builds the list of useful links. */
    private List<LinkItem> buildAllLinks() {
        List<LinkItem> list = new ArrayList<>();

        // Global

        list.add(new LinkItem(
                "global_plan_zajec",
                "Plan zajęć (Rozkład)",
                "https://plan.zut.edu.pl",
                "Aktualny rozkład zajęć dla wszystkich kierunków i grup.",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_usosweb",
                "USOSweb / e-dziekanat",
                "https://usosweb.zut.edu.pl",
                "Oceny, zapisy na przedmioty, płatności, wnioski.",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_office365",
                "Poczta / Office 365",
                "https://o365.zut.edu.pl",
                "Poczta studencka, Teams, OneDrive (Office 365).",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_elearning",
                "E-learning ZUT (Moodle)",
                "https://e-edukacja.zut.edu.pl",
                "Kursy online, materiały wykładowe, przesyłanie prac.",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_zut_home",
                "Strona główna ZUT",
                "https://www.zut.edu.pl",
                "Aktualności uczelniane i komunikaty rektora.",
                LinkScope.GLOBAL,
                null,
                null));

        // IT / Account

        list.add(new LinkItem(
                "global_konto_zut",
                "Zarządzanie kontem ZUT",
                "https://konto.zut.edu.pl",
                "Zmiana hasła, odzyskiwanie dostępu, konfiguracja WiFi (Eduroam).",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_mleg",
                "mLegitymacja",
                "https://mlegitymacja.zut.edu.pl",
                "Aktywacja i przedłużanie mLegitymacji w aplikacji mObywatel.",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_uci",
                "Pomoc IT (UCI)",
                "https://uci.zut.edu.pl",
                "Instrukcje konfiguracji sieci, VPN, zgłaszanie awarii.",
                LinkScope.GLOBAL,
                null,
                null));

        // Student life

        list.add(new LinkItem(
                "global_library",
                "Biblioteka Główna",
                "https://bg.zut.edu.pl",
                "Katalog książek, dostęp do baz danych i artykułów.",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_pomoc_materialna",
                "Stypendia i pomoc materialna",
                "https://www.zut.edu.pl/zut-studenci/pomoc-materialna-akademiki-kredyty.html",
                "Regulaminy, terminy i wzory wniosków o stypendia.",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_osiedle_studenckie",
                "Akademiki (Osiedle Studenckie)",
                "https://osiedlestudenckie.zut.edu.pl",
                "Opłaty, kwaterowanie, regulaminy domów studenckich.",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_samorzad",
                "Samorząd Studencki",
                "https://www.samorzad.zut.edu.pl",
                "Wydarzenia, juwenalia, prawa studenta, koła naukowe.",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_biuro_karier",
                "Biuro Karier",
                "https://biurokarier.zut.edu.pl",
                "Oferty pracy, staże, targi pracy.",
                LinkScope.GLOBAL,
                null,
                null));

        list.add(new LinkItem(
                "global_prk_portal",
                "Sylabusy i programy (PRK)",
                "https://prk.zut.edu.pl",
                "Wyszukiwarka sylabusów (kart przedmiotów) i programów studiów.",
                LinkScope.GLOBAL,
                null,
                null));

        // Faculties

        // Faculty of Computer Science (WI)
        list.add(new LinkItem(
                "inf_wi_home",
                "Wydział Informatyki (WI)",
                "https://www.wi.zut.edu.pl",
                "Strona główna wydziału, ogłoszenia dziekanatu.",
                LinkScope.FACULTY,
                "WI",
                null));

        list.add(new LinkItem(
                "inf_wi_students",
                "WI – Strefa Studenta",
                "https://www.wi.zut.edu.pl/pl/dla-studenta",
                "Plany studiów, dyplomowanie, druki do pobrania.",
                LinkScope.FACULTY,
                "WI",
                null));

        // Faculty of Economics (WNEiZ)
        list.add(new LinkItem(
                "eko_faculty_home",
                "Wydział Ekonomiczny",
                "https://ekonomia.zut.edu.pl",
                "Aktualności wydziałowe i informacje dla studentów.",
                LinkScope.FACULTY,
                "WNEIZ",
                null));

        list.add(new LinkItem(
                "eko_plany",
                "Wydział Ekonomiczny – Strefa studenta",
                "https://ekonomia.zut.edu.pl/strona-studentow",
                "Organizacja roku, praktyki, plany i dokumenty dla studentów.",
                LinkScope.FACULTY,
                "WNEIZ",
                null));

        // Faculty of Mechanical Engineering (WIMiM)
        list.add(new LinkItem(
                "mech_faculty_home",
                "Wydział Inżynierii Mech. i Mechatroniki",
                "https://wimim.zut.edu.pl",
                "Strona wydziału WIMiM.",
                LinkScope.FACULTY,
                "WIMIM",
                null));

        // Faculty of Electrical Engineering (WE)
        list.add(new LinkItem(
                "we_faculty_home",
                "Wydział Elektryczny (WE)",
                "https://we.zut.edu.pl",
                "Strona wydziału, aktualności dla elektryków i automatyków.",
                LinkScope.FACULTY,
                "WE",
                null));

        // Faculty of Civil Engineering and Environmental Engineering (WBiIŚ)
        list.add(new LinkItem(
                "wbiis_faculty_home",
                "Wydział Budownictwa i Inżynierii Środowiska",
                "https://wbiis.zut.edu.pl",
                "Strona wydziału WBiIŚ.",
                LinkScope.FACULTY,
                "WBIA",
                null));

        // Faculty of Architecture (WA)
        list.add(new LinkItem(
                "wa_faculty_home",
                "Wydział Architektury",
                "https://wa.zut.edu.pl",
                "Strona wydziału Architektury (WA).",
                LinkScope.FACULTY,
                "WBIA",
                null));

        return list;
    }

    // Model + adapter

    enum LinkScope {
        GLOBAL,
        FACULTY,
        MAJOR,
        OTHER
    }

    static class LinkItem {
        String id;
        String title;
        String url;
        String description;
        LinkScope scope;
        String facultyCode; // e.g. WI, WNEIZ, WIMIM
        String majorCode; // e.g. INF, EKO, MIB

        int priorityWeight = 3;
        boolean highlight = false;

        LinkItem(String id,
                String title,
                String url,
                String description,
                LinkScope scope,
                String facultyCode,
                String majorCode) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.description = description;
            this.scope = scope;
            this.facultyCode = facultyCode;
            this.majorCode = majorCode;
        }
    }

    private class LinksAdapter extends RecyclerView.Adapter<LinksAdapter.VH> {

        private final List<LinkItem> data;

        LinksAdapter(List<LinkItem> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.row_useful_link, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            LinkItem li = data.get(position);

            h.title.setText(li.title);
            h.desc.setText(li.description != null ? li.description : "");
            h.url.setText(li.url);

            if (li.highlight) {
                h.badge.setVisibility(View.VISIBLE);
            } else {
                h.badge.setVisibility(View.GONE);
            }

            h.itemView.setOnClickListener(v -> {
                try {
                    Intent i = new Intent(v.getContext(), WebLinkActivity.class);
                    i.putExtra(WebLinkActivity.EXTRA_TITLE, li.title);
                    i.putExtra(WebLinkActivity.EXTRA_URL, li.url);
                    v.getContext().startActivity(i);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        @Override
        public int getItemCount() {
            return data != null ? data.size() : 0;
        }

        class VH extends RecyclerView.ViewHolder {
            TextView title;
            TextView desc;
            TextView url;
            TextView badge;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.linkTitle);
                desc = itemView.findViewById(R.id.linkDesc);
                url = itemView.findViewById(R.id.linkUrl);
                badge = itemView.findViewById(R.id.linkBadge);
            }
        }
    }
}
