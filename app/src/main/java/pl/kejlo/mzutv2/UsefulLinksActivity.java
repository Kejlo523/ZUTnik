package pl.kejlo.mzutv2;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
 *   remaining links are below.
 * - No spinners / selectors – priority is based on data from MzutSession.
 *
 * Expected layout: res/layout/activity_useful_links.xml
 *  - DrawerLayout @+id/drawerLayout
 *  - NavigationView @+id/navigationView
 *  - Toolbar @+id/toolbar
 *  - RecyclerView @+id/listLinks
 *  - TextView @+id/tvLinksEmpty (optional "no data" label)
 */
public class UsefulLinksActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private Toolbar toolbar;

    private RecyclerView listLinks;
    private TextView tvEmpty;

    private LinksAdapter adapter;
    private final List<LinkItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_useful_links);

        drawerLayout = findViewById(R.id.drawerLayout);
        navigationView = findViewById(R.id.navigationView);
        toolbar = findViewById(R.id.toolbar);
        listLinks = findViewById(R.id.listLinks);
        tvEmpty = findViewById(R.id.tvLinksEmpty);

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
    public boolean dispatchTouchEvent(MotionEvent ev) {
        NavDrawerHelper.handleDrawerSwipe(this, drawerLayout, ev);
        return super.dispatchTouchEvent(ev);
    }

    // Main logic

    private void loadAndSortLinksForUser() {
        // 1) Build full (but already trimmed) list of links
        List<LinkItem> all = buildAllLinks();

        if (all.isEmpty()) {
            if (tvEmpty != null) {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText("Brak zdefiniowanych linków.");
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

            // Informatyka – Faculty of Computer Science (WI)
            if (l.contains("informatyka")) {
                majors.add("INF");
                faculties.add("WI");
            }

            // Ekonomia – Faculty of Economics / WNEIZ
            if (l.contains("ekonomia")) {
                majors.add("EKO");
                faculties.add("WNEIZ");
            }

            // Mechanika i budowa maszyn – WIMiM (example)
            if (l.contains("mechanika") || l.contains("budowa maszyn")) {
                majors.add("MIB");
                faculties.add("WIMIM");
            }

            // Additional majors can be mapped here
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

    /**
     * Defines selected, most frequently used links.
     */
    private List<LinkItem> buildAllLinks() {
        List<LinkItem> list = new ArrayList<>();

        // Global – general ZUT pages / systems
        list.add(new LinkItem(
                "global_zut_home",
                "Strona główna ZUT",
                "https://www.zut.edu.pl",
                "Aktualności, ogólne informacje o uczelni.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_usosweb",
                "USOSweb / e-dziekanat",
                "https://usosweb.zut.edu.pl",
                "Oceny, plan studiów, dane osobowe, mLegitymacja.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_elearning",
                "E-learning ZUT (e-edukacja)",
                "https://e-edukacja.zut.edu.pl",
                "Platforma e-learningowa ZUT (Moodle, kursy online).",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_library",
                "Biblioteka ZUT",
                "https://bg.zut.edu.pl",
                "Biblioteka główna, zasady korzystania, katalog online.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_mleg",
                "mLegitymacja studencka",
                "https://mlegitymacja.zut.edu.pl",
                "mLegitymacja ZUT – informacje o aktywacji i przedłużaniu.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_office365",
                "Poczta studencka / Office 365",
                "https://outlook.office.com",
                "Poczta studencka, Teams, OneDrive – pakiet Office 365.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_zut_studinfo",
                "StudInfo – usługi IT dla studenta",
                "https://uci.zut.edu.pl/studinfo.html",
                "Zbiór linków: poczta, e-dziekanat, e-edukacja, e-dysk, WiFi i inne usługi IT ZUT.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_uci",
                "Uczelniane Centrum Informatyki (UCI)",
                "https://www.zut.edu.pl/uczelnia/jednostki-uczelni/uczelniane-centrum-informatyki.html",
                "Pomoc IT, hasła, sieć, konfiguracja poczty i usług informatycznych.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_edysk_student",
                "e-Dysk – przestrzeń na pliki (studenci)",
                "https://edysk2.zut.edu.pl",
                "Uczelniana chmura plików (eDysk) dla studentów – dostęp po loginie USK ZUT.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        // Student area / information
        list.add(new LinkItem(
                "global_zut_students_news",
                "ZUT – aktualności dla studentów",
                "https://www.zut.edu.pl/zut-studenci/aktualnosci.html",
                "Komunikaty i aktualności uczelniane kierowane do studentów.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_zut_praca_kariera",
                "Praca i Kariera",
                "https://www.zut.edu.pl/zut-studenci/praca-i-kariera.html",
                "Oferty pracy, praktyk oraz informacje o Biurze Karier i AIP.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        // Financial aid / scholarships
        list.add(new LinkItem(
                "global_pomoc_materialna",
                "Pomoc materialna, akademiki, kredyty",
                "https://www.zut.edu.pl/zut-studenci/pomoc-materialna-akademiki-kredyty/aktualnosci.html",
                "Ogólne informacje o stypendiach, akademikach i kredytach studenckich (aktualności).",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_pomoc_wzory_wnioskow",
                "Wzory wniosków o stypendia",
                "https://www.zut.edu.pl/zut-studenci/pomoc-materialna-akademiki-kredyty/wzory-wnioskow-o-stypendia-20252026.html",
                "Aktualne formularze wniosków o stypendia i inne świadczenia.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        // Dorms / health
        list.add(new LinkItem(
                "global_osiedle_studenckie",
                "Osiedle Studenckie ZUT",
                "https://osiedlestudenckie.zut.edu.pl",
                "Główny portal domów studenckich ZUT – informacje o akademikach, regulaminy, aktualności.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_akademiki_kontakt",
                "Akademiki – dane kontaktowe",
                "https://osiedlestudenckie.zut.edu.pl/akademiki-kontakt.html",
                "Adresy, telefony i informacje o poszczególnych domach studenckich.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_przychodnie_zut",
                "Przychodnie ZUT",
                "https://www.zut.edu.pl/zut-studenci/kontakt/przychodnie-zut.html",
                "Informacje o przychodniach związanych z ZUT, adresy i godziny przyjęć.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        // Career / internships
        list.add(new LinkItem(
                "global_biuro_karier",
                "Akademickie Biuro Karier",
                "https://biurokarier.zut.edu.pl",
                "Oferty pracy, praktyk i wsparcie w planowaniu kariery.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        list.add(new LinkItem(
                "global_praktyki_zawodowe",
                "Praktyki zawodowe – informacje",
                "https://www.zut.edu.pl/zut-studenci/studia/praktyki-zawodowe.html",
                "Zasady realizacji praktyk zawodowych, informacje dla studentów wszystkich kierunków.",
                LinkScope.GLOBAL,
                null,
                null
        ));

        // PRK / curricula
        list.add(new LinkItem(
                "global_prk_portal",
                "PRK ZUT – programy studiów i sylabusy",
                "https://prk.zut.edu.pl",
                "Portal z planami studiów, sylabusami i efektami uczenia dla wszystkich kierunków ZUT (PRK).",
                LinkScope.GLOBAL,
                null,
                null
        ));

        // Informatyka / Faculty of Computer Science (INF / WI)
        list.add(new LinkItem(
                "inf_wi_home",
                "Wydział Informatyki (WI)",
                "https://wi.zut.edu.pl",
                "Strona wydziału, informacje dla studentów informatyki.",
                LinkScope.FACULTY,
                "WI",
                null
        ));

        list.add(new LinkItem(
                "inf_wi_students",
                "WI – sprawy studenckie",
                "https://wi.zut.edu.pl/dla-studenta",
                "Dziekanat, regulaminy, organizacja studiów dla studentów WI.",
                LinkScope.FACULTY,
                "WI",
                null
        ));

        list.add(new LinkItem(
                "inf_wiki_zmsi",
                "WikiZMSI – materiały do informatyki",
                "https://wikizmsi.zut.edu.pl/wiki/Strona_g%C5%82%C3%B3wna",
                "Nieoficjalne wiki z materiałami, zadaniami i kursami WI.",
                LinkScope.MAJOR,
                "WI",
                "INF"
        ));

        list.add(new LinkItem(
                "prk_inf_n1",
                "PRK – Informatyka (N1, WI)",
                "https://prk.zut.edu.pl/pl/2024-2025/wydzial-informatyki/informatyka-N1/",
                "Opis programu studiów Informatyka (niestacjonarne I stopnia, WI) – długość studiów, ECTS, efekty uczenia.",
                LinkScope.MAJOR,
                "WI",
                "INF"
        ));

        // Ekonomia / WNEiZ (EKO / WNEIZ)
        list.add(new LinkItem(
                "eko_faculty_home",
                "Wydział Ekonomiczny / WNEiZ",
                "https://www.wneiz.zut.edu.pl",
                "Strona wydziału, informacje dla studentów kierunków ekonomicznych.",
                LinkScope.FACULTY,
                "WNEIZ",
                null
        ));

        list.add(new LinkItem(
                "eko_students",
                "Ekonomia – informacje dla studentów",
                "https://www.wneiz.zut.edu.pl/dla-studenta",
                "Sprawy studenckie, plan studiów, organizacja roku akademickiego.",
                LinkScope.MAJOR,
                "WNEIZ",
                "EKO"
        ));

        list.add(new LinkItem(
                "eko_major_desc",
                "Kierunek Ekonomia – opis",
                "https://www.zut.edu.pl/studia/kierunki-studiow/ekonomia.html",
                "Opis kierunku i programu kształcenia Ekonomia.",
                LinkScope.MAJOR,
                "WNEIZ",
                "EKO"
        ));

        list.add(new LinkItem(
                "prk_eko_s1",
                "PRK – Ekonomia (S1, Wydział Ekonomiczny)",
                "https://prk.zut.edu.pl/pl/2024-2025/wydzial-ekonomiczny/ekonomia-S1/",
                "Opis programu studiów Ekonomia (stacjonarne I stopnia, Wydział Ekonomiczny).",
                LinkScope.MAJOR,
                "WNEIZ",
                "EKO"
        ));

        // WIMiM – example of another faculty
        list.add(new LinkItem(
                "mech_faculty_home",
                "Wydział Inżynierii Mechanicznej i Mechatroniki (WIMiM)",
                "https://wimim.zut.edu.pl",
                "Strona wydziału dla kierunków mechanicznych i mechatroniki.",
                LinkScope.FACULTY,
                "WIMIM",
                null
        ));

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
        String majorCode;   // e.g. INF, EKO, MIB

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
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(li.url));
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
