package pl.kejlo.zutnik;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local mock data for preview/demo mode. Keeps all main tabs populated without
 * USOS credentials.
 */
final class DemoDataProvider {

    static final String ALBUM_NUMBER = "57708";
    static final String DEMO_USER_ID = "demo";
    static final String DEMO_USERNAME = "Jan Kowalski";
    static final String DEMO_STUDY_ID = "demo-informatyka";

    private DemoDataProvider() {
    }

    static void populateSession(ZutnikSession session) {
        if (session == null) {
            return;
        }
        session.setStudentNumber(ALBUM_NUMBER);
        List<Study> studies = createStudies();
        session.setStudies(studies);
        session.setActiveStudyIndex(0);
    }

    static List<Study> createStudies() {
        List<Study> studies = new ArrayList<>();
        Study primary = new Study();
        primary.przynaleznoscId = DEMO_STUDY_ID;
        primary.label = "Informatyka (stacjonarne, I stopień)";
        studies.add(primary);

        Study secondary = new Study();
        secondary.przynaleznoscId = "demo-informatyka-mgr";
        secondary.label = "Informatyka (stacjonarne, II stopień)";
        studies.add(secondary);
        return studies;
    }

    static List<Semester> createSemesters() {
        List<Semester> semesters = new ArrayList<>();

        Semester s1 = new Semester();
        s1.listaSemestrowId = "demo-2024Z";
        s1.nrSemestru = "4";
        s1.pora = "zimowy";
        s1.rokAkademicki = "2024/2025";
        s1.status = "Zakończony";
        semesters.add(s1);

        Semester s2 = new Semester();
        s2.listaSemestrowId = "demo-2025L";
        s2.nrSemestru = "5";
        s2.pora = "letni";
        s2.rokAkademicki = "2024/2025";
        s2.status = "Zakończony";
        semesters.add(s2);

        Semester s3 = new Semester();
        s3.listaSemestrowId = "demo-2025Z";
        s3.nrSemestru = "5";
        s3.pora = "zimowy";
        s3.rokAkademicki = "2025/2026";
        s3.status = "Aktywny";
        semesters.add(s3);

        return semesters;
    }

    static List<Grade> loadGradesForSemester(String semesterId) {
        if (semesterId == null) {
            return Collections.emptyList();
        }
        switch (semesterId) {
            case "demo-2024Z":
                return gradesSemester2024Z();
            case "demo-2025L":
                return gradesSemester2025L();
            case "demo-2025Z":
                return gradesSemester2025Z();
            default:
                return Collections.emptyList();
        }
    }

    static List<Grade> loadCurrentGrades() {
        return gradesSemester2025Z();
    }

    static GradesRepository.CreditSummary loadCreditSummary() {
        List<GradesRepository.ProgrammeCredit> credits = new ArrayList<>();
        credits.add(new GradesRepository.ProgrammeCredit(
                DEMO_STUDY_ID,
                "Informatyka (stacjonarne, I stopień)",
                87.5d));
        credits.add(new GradesRepository.ProgrammeCredit(
                "demo-informatyka-mgr",
                "Informatyka (stacjonarne, II stopień)",
                0d));
        return new GradesRepository.CreditSummary(DEMO_STUDY_ID, 87.5d, 112.0d, credits);
    }

    static StudiesInfoRepository.StudyDetails loadStudyDetails() {
        StudiesInfoRepository.StudyDetails details = new StudiesInfoRepository.StudyDetails();
        details.album = ALBUM_NUMBER;
        details.kierunek = "Informatyka";
        details.status = "Aktywny";
        details.rokAkademicki = "2025/2026";
        details.semestrLabel = "zimowy";
        details.ectsProgramme = 87.5d;
        details.ectsOverall = 112.0d;
        details.elsId = "ELS-" + ALBUM_NUMBER;
        details.elsExpirationDate = "31.10.2026";
        details.elsStatus = "Aktywna";
        return details;
    }

    static List<StudiesInfoRepository.StudyHistoryItem> loadStudyHistory() {
        List<StudiesInfoRepository.StudyHistoryItem> history = new ArrayList<>();

        StudiesInfoRepository.StudyHistoryItem current = new StudiesInfoRepository.StudyHistoryItem();
        current.label = "Informatyka (stacjonarne, I stopień)";
        current.status = "Aktywny";
        history.add(current);

        StudiesInfoRepository.StudyHistoryItem previous = new StudiesInfoRepository.StudyHistoryItem();
        previous.label = "Informatyka (stacjonarne, I stopień)";
        previous.status = "Aktywny";
        history.add(previous);

        StudiesInfoRepository.StudyHistoryItem exchange = new StudiesInfoRepository.StudyHistoryItem();
        exchange.label = "Erasmus – Technische Universität Berlin";
        exchange.status = "Ukończony";
        history.add(exchange);

        return history;
    }

    static List<FinanceRecord> loadFinanceRecords() {
        List<FinanceRecord> records = new ArrayList<>();

        records.add(payment(
                "demo-pay-1",
                "Opłata rekrutacyjna",
                "85,00 zł",
                "85,00 zł",
                "15.09.2024",
                "15.09.2024",
                "0,00 zł",
                "12 3456 7890 1234 5678 9012 3456"));

        records.add(payment(
                "demo-pay-2",
                "Czesne – semestr letni 2024/2025",
                "1 950,00 zł",
                "1 950,00 zł",
                "15.03.2025",
                "10.03.2025",
                "0,00 zł",
                "12 3456 7890 1234 5678 9012 3456"));

        records.add(payment(
                "demo-pay-3",
                "Wydanie legitymacji ELS",
                "22,00 zł",
                "22,00 zł",
                "01.10.2025",
                "28.09.2025",
                "0,00 zł",
                "12 3456 7890 1234 5678 9012 3456"));

        records.add(payment(
                "demo-pay-4",
                "Czesne – semestr zimowy 2025/2026",
                "2 100,00 zł",
                "600,00 zł",
                "15.10.2025",
                null,
                "1 500,00 zł",
                "12 3456 7890 1234 5678 9012 3456"));

        records.add(payment(
                "demo-pay-5",
                "Opłata za wydruk suplementu",
                "15,00 zł",
                "20,00 zł",
                "20.05.2025",
                "18.05.2025",
                "-5,00 zł",
                "12 3456 7890 1234 5678 9012 3456"));

        records.add(payment(
                "demo-pay-6",
                "Opłata za powtórzenie egzaminu",
                "120,00 zł",
                "0,00 zł",
                "30.11.2025",
                null,
                "120,00 zł",
                "12 3456 7890 1234 5678 9012 3456"));

        return records;
    }

    private static FinanceRecord payment(
            String id,
            String title,
            String amount,
            String paid,
            String dueDate,
            String paidDate,
            String balance,
            String account) {
        FinanceRecord record = new FinanceRecord();
        record.recordId = id;
        record.title = title;
        record.amountText = amount;
        record.paidText = paid;
        record.dueDateText = dueDate;
        record.paidDateText = paidDate;
        record.balanceText = balance;
        record.accountText = account;
        record.amountValue = FinanceRecord.parseAmount(amount);
        record.paidValue = FinanceRecord.parseAmount(paid);
        record.balanceValue = FinanceRecord.parseAmount(balance);
        return record;
    }

    private static List<Grade> gradesSemester2024Z() {
        List<Grade> grades = new ArrayList<>();
        grades.add(finalGrade("Analiza matematyczna", "4.5", 6, "15.02.2025", "dr hab. Anna Nowak"));
        grades.add(partial("Analiza matematyczna", "4.0", "Kolokwium", "20.01.2025"));
        grades.add(finalGrade("Fizyka", "4.0", 5, "12.02.2025", "dr Piotr Wiśniewski"));
        grades.add(finalGrade("Podstawy elektroniki", "3.5", 4, "18.02.2025", "dr inż. Marek Zieliński"));
        grades.add(finalGrade("Algorytmy i struktury danych", "5.0", 6, "10.02.2025", "prof. dr hab. Ewa Kowalczyk"));
        grades.add(partial("Algorytmy i struktury danych", "5.0", "Projekt", "28.01.2025"));
        grades.add(finalGrade("Język angielski B2", "zal", 2, "05.02.2025", "mgr Katarzyna Lewandowska"));
        return grades;
    }

    private static List<Grade> gradesSemester2025L() {
        List<Grade> grades = new ArrayList<>();
        grades.add(finalGrade("Bazy danych", "4.5", 5, "20.06.2025", "dr inż. Tomasz Jankowski"));
        grades.add(partial("Bazy danych", "4.0", "Laboratorium", "02.06.2025"));
        grades.add(finalGrade("Systemy operacyjne", "4.0", 5, "18.06.2025", "dr hab. Krzysztof Mazur"));
        grades.add(finalGrade("Sieci komputerowe", "3.5", 5, "25.06.2025", "dr inż. Agnieszka Wójcik"));
        grades.add(partial("Sieci komputerowe", "4.0", "Ćwiczenia", "10.06.2025"));
        grades.add(finalGrade("Prawo w informatyce", "5.0", 2, "12.06.2025", "mgr prawn. Joanna Szymańska"));
        grades.add(finalGrade("Wychowanie fizyczne", "zal", 1, "08.06.2025", "mgr Adam Krawczyk"));
        return grades;
    }

    private static List<Grade> gradesSemester2025Z() {
        List<Grade> grades = new ArrayList<>();
        grades.add(finalGrade("Programowanie obiektowe", "4.5", 6, "12.12.2025", "dr inż. Michał Grabowski"));
        grades.add(partial("Programowanie obiektowe", "4.0", "Projekt zespołowy", "25.11.2025"));
        grades.add(finalGrade("Inżynieria oprogramowania", "4.0", 5, "20.01.2026", "dr hab. Paweł Lis"));
        grades.add(partial("Inżynieria oprogramowania", "5.0", "Warsztaty", "15.12.2025"));
        grades.add(finalGrade("Architektura komputerów", "3.5", 4, "15.01.2026", "dr inż. Robert Adamczyk"));
        grades.add(finalGrade("Teoria grafów", "5.0", 4, "08.01.2026", "prof. dr hab. Maria Dąbrowska"));
        grades.add(finalGrade("Etyka w IT", "zal", 2, "22.12.2025", "mgr Sylwia Ostrowska"));
        grades.add(partial("Programowanie mobilne", "4.5", "Laboratorium", "10.01.2026"));
        return grades;
    }

    private static Grade finalGrade(
            String subject,
            String symbol,
            double ects,
            String date,
            String teacher) {
        Grade grade = baseGrade(subject, symbol, ects, date, teacher);
        grade.type = "Ocena końcowa";
        grade.countsIntoAverage = !"zal".equalsIgnoreCase(symbol);
        return grade;
    }

    private static Grade partial(String subject, String symbol, String type, String date) {
        Grade grade = baseGrade(subject, symbol, 0, date, null);
        grade.type = type;
        grade.countsIntoAverage = false;
        return grade;
    }

    private static Grade baseGrade(
            String subject,
            String symbol,
            double ects,
            String date,
            String teacher) {
        Grade grade = new Grade();
        grade.subjectName = subject;
        grade.courseId = "demo-" + normalizeKey(subject);
        grade.grade = symbol;
        grade.weight = ects;
        grade.date = date;
        grade.dateAcquisition = date;
        grade.teacher = teacher;
        grade.passes = !"2".equals(symbol) && !"nd".equalsIgnoreCase(symbol);
        grade.gradeDescription = describeGrade(symbol);
        return grade;
    }

    private static String describeGrade(String symbol) {
        if (symbol == null) {
            return "";
        }
        switch (symbol.toLowerCase()) {
            case "5.0":
            case "5":
                return "Bardzo dobry";
            case "4.5":
                return "Dobry plus";
            case "4.0":
            case "4":
                return "Dobry";
            case "3.5":
                return "Dostateczny plus";
            case "3.0":
            case "3":
                return "Dostateczny";
            case "zal":
                return "Zaliczony";
            default:
                return symbol;
        }
    }

    private static String normalizeKey(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}
