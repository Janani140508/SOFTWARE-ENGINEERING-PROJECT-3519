import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.*;

public class TriageEngine {
    private static final int PORT = 8000;
    private static final int DOCTOR_CAPACITY_LIMIT = 5;

    static class DoctorProfile {
        int id;
        String name;
        String room;
        String specialty;

        DoctorProfile(int id, String name, String room, String specialty) {
            this.id = id;
            this.name = name;
            this.room = room;
            this.specialty = specialty;
        }
    }

    private static final List<DoctorProfile> ROSTER = Arrays.asList(
        new DoctorProfile(1, "Dr. Sharma", "Room 101", "Trauma & General"),
        new DoctorProfile(2, "Dr. Verma", "Room 102", "Emergency Internal"),
        new DoctorProfile(3, "Dr. Patel", "Room 103", "Cardiology"),
        new DoctorProfile(4, "Dr. Iyer", "Room 104", "Orthopedics"),
        new DoctorProfile(5, "Dr. Rao", "Room 105", "Pediatrics"),
        new DoctorProfile(6, "Dr. Chatterjee", "Room 106", "Neurology"),
        new DoctorProfile(7, "Dr. Khan", "Room 107", "Critical Care"),
        new DoctorProfile(8, "Dr. Gupta", "Room 108", "ENT & Acute Surgery")
    );

    static class Patient implements Comparable<Patient> {
        String token;
        int severity;
        int waitTimeMinutes;
        String doctor;
        String room;
        long admittedAt;

        Patient(String token, int severity, String doctor, String room) {
            this.token = token;
            this.severity = severity;
            this.waitTimeMinutes = 0;
            this.doctor = doctor;
            this.room = room;
            this.admittedAt = System.currentTimeMillis();
        }

        public double getPriorityScore() {
            // Severity scale (1-5) * 10 + capped aging bonus
            // Ensures Severity 5 (50 pts) always preempts lower-acuity patients
            double agingBonus = Math.min(this.waitTimeMinutes * 0.2, 5.0);
            return (this.severity * 10.0) + agingBonus;
        }

        @Override
        public int compareTo(Patient other) {
            int diff = Double.compare(other.getPriorityScore(), this.getPriorityScore());
            if (diff == 0) {
                return Long.compare(this.admittedAt, other.admittedAt);
            }
            return diff;
        }
    }

    private static final PriorityQueue<Patient> queue = new PriorityQueue<>();
    private static Patient lastCalled = null;

    public static void main(String[] args) throws IOException {
        seedInitialPatients();
        startDynamicAgingDaemon();
        startHttpServer();
        exportStateToJson();

        printConsoleFacultyDashboard();

        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");

            if (parts[0].equalsIgnoreCase("add") && parts.length >= 3) {
                String token = parts[1];
                int severity = Integer.parseInt(parts[2]);
                Integer preferredDocIndex = null;
                if (parts.length >= 4) {
                    try {
                        preferredDocIndex = Integer.parseInt(parts[3]) - 1;
                    } catch (NumberFormatException ignored) {}
                }
                registerPatient(token, severity, preferredDocIndex);
            } else if (parts[0].equalsIgnoreCase("next")) {
                Integer docIndex = null;
                if (parts.length >= 2) {
                    try {
                        docIndex = Integer.parseInt(parts[1]) - 1;
                    } catch (NumberFormatException ignored) {}
                }
                callNextPatient(docIndex);
            } else if (parts[0].equalsIgnoreCase("list")) {
                printQueueRoster();
            } else if (parts[0].equalsIgnoreCase("docs")) {
                printDoctorTable();
            } else if (parts[0].equalsIgnoreCase("exit")) {
                System.exit(0);
            }
        }
    }

    private static void printConsoleFacultyDashboard() {
        System.out.println("=========================================================================================");
        System.out.println("            8-DOCTOR REAL-TIME TRIAGE ENGINE ACTIVE (PORT " + PORT + ")");
        System.out.println("=========================================================================================");
        printDoctorTable();
        System.out.println("-----------------------------------------------------------------------------------------");
        printQueueRoster();
        System.out.println("-----------------------------------------------------------------------------------------");
        System.out.println(" EVALUATION COMMANDS FOR FACULTY:");
        System.out.println("   add <Token> <Severity 1-5> <Doc# 1-8>  -> e.g. add TK-999 5 2");
        System.out.println("   next <Doc# 1-8>                       -> e.g. next 2 (calls next patient for Dr. Verma)");
        System.out.println("   list                                  -> re-prints current waiting queue and URLs");
        System.out.println("   docs                                  -> displays doctor number mapping table");
        System.out.println("=========================================================================================");
    }

    private static void printDoctorTable() {
        System.out.println(" DOCTOR DIRECTORY & ROOM ASSIGNMENTS:");
        System.out.printf("  %-4s | %-16s | %-10s | %s\n", "NO.", "PHYSICIAN", "ROOM", "SPECIALTY");
        System.out.println("  ------------------------------------------------------------------");
        for (DoctorProfile doc : ROSTER) {
            System.out.printf("  [%d]  | %-16s | %-10s | %s\n", doc.id, doc.name, doc.room, doc.specialty);
        }
    }

    private static void printQueueRoster() {
        List<Patient> sorted = new ArrayList<>(queue);
        Collections.sort(sorted);
        System.out.println(" LIVE PATIENT STATUS & BROWSER URLs:");
        System.out.printf("  %-8s | %-4s | %-8s | %-16s | %-9s | %s\n",
            "TOKEN", "SEV", "SCORE", "DOCTOR", "ROOM", "PATIENT PASS LINK");
        System.out.println("  ---------------------------------------------------------------------------------------");
        for (Patient p : sorted) {
            System.out.printf("  %-8s | %-4d | %-8.1f | %-16s | %-9s | http://localhost:8000/?token=%s\n",
                p.token, p.severity, p.getPriorityScore(), p.doctor, p.room, p.token);
        }
    }

    private static synchronized void registerPatient(String token, int severity, Integer preferredDocIndex) {
        DoctorProfile selectedDoc;

        if (preferredDocIndex != null && preferredDocIndex >= 0 && preferredDocIndex < ROSTER.size()) {
            selectedDoc = ROSTER.get(preferredDocIndex);
        } else {
            selectedDoc = ROSTER.stream()
                .min(Comparator.comparingLong(doc -> queue.stream().filter(p -> p.doctor.equals(doc.name)).count()))
                .orElse(ROSTER.get(0));
        }

        Patient p = new Patient(token, severity, selectedDoc.name, selectedDoc.room);
        queue.add(p);
        exportStateToJson();

        System.out.println("\n [+] ADMITTED: " + token + " (Severity: " + severity + ")");
        System.out.println("     Doctor  : [" + selectedDoc.id + "] " + selectedDoc.name + " (" + selectedDoc.room + ")");
        System.out.println("     Pass URL: http://localhost:8000/?token=" + token + "\n");
    }

    private static synchronized void callNextPatient(Integer docIndex) {
        if (queue.isEmpty()) {
            System.out.println(" [!] Queue is empty.");
            return;
        }

        Patient target = null;
        if (docIndex != null && docIndex >= 0 && docIndex < ROSTER.size()) {
            String docName = ROSTER.get(docIndex).name;
            for (Patient p : queue) {
                if (p.doctor.equals(docName)) {
                    target = p;
                    break;
                }
            }
            if (target != null) {
                queue.remove(target);
            } else {
                System.out.println(" [!] No patients waiting for " + docName);
                return;
            }
        } else {
            target = queue.poll();
        }

        lastCalled = target;
        exportStateToJson();
        System.out.println("\n [CALL] >>> " + target.token + " proceed to " + target.doctor + " (" + target.room + ")\n");
    }

    private static void startDynamicAgingDaemon() {
        Thread daemon = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10000); // 10-second aging interval
                    synchronized (TriageEngine.class) {
                        if (!queue.isEmpty()) {
                            List<Patient> temp = new ArrayList<>();
                            while (!queue.isEmpty()) {
                                Patient p = queue.poll();
                                p.waitTimeMinutes += 1;
                                temp.add(p);
                            }
                            queue.addAll(temp);
                            exportStateToJson();
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        daemon.setDaemon(true);
        daemon.start();
    }

    private static synchronized void exportStateToJson() {
        List<Patient> sorted = new ArrayList<>(queue);
        Collections.sort(sorted);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"timestamp\": ").append(System.currentTimeMillis()).append(",\n");
        sb.append("  \"last_called\": ");
        if (lastCalled == null) {
            sb.append("null,\n");
        } else {
            sb.append("{")
              .append("\"token\":\"").append(lastCalled.token).append("\",")
              .append("\"doctor\":\"").append(lastCalled.doctor).append("\",")
              .append("\"room\":\"").append(lastCalled.room).append("\"")
              .append("},\n");
        }

        sb.append("  \"queue\": [\n");
        for (int i = 0; i < sorted.size(); i++) {
            Patient p = sorted.get(i);
            sb.append("    {")
              .append("\"token\":\"").append(p.token).append("\",")
              .append("\"severity\":").append(p.severity).append(",")
              .append("\"waitTime\":").append(p.waitTimeMinutes).append(",")
              .append("\"score\":").append(String.format(Locale.US, "%.1f", p.getPriorityScore())).append(",")
              .append("\"doctor\":\"").append(p.doctor).append("\",")
              .append("\"room\":\"").append(p.room).append("\"")
              .append("}").append(i < sorted.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}");

        try (FileWriter fw = new FileWriter("live_queue.json")) {
            fw.write(sb.toString());
        } catch (IOException e) {
            System.err.println("JSON export error: " + e.getMessage());
        }
    }

    private static void seedInitialPatients() {
        queue.add(new Patient("TK-101", 2, "Dr. Sharma", "Room 101"));
        queue.add(new Patient("TK-102", 4, "Dr. Sharma", "Room 101"));

        queue.add(new Patient("TK-103", 3, "Dr. Verma", "Room 102"));
        queue.add(new Patient("TK-104", 1, "Dr. Verma", "Room 102"));

        queue.add(new Patient("TK-105", 4, "Dr. Patel", "Room 103"));
        queue.add(new Patient("TK-106", 2, "Dr. Patel", "Room 103"));

        queue.add(new Patient("TK-107", 3, "Dr. Iyer", "Room 104"));
        queue.add(new Patient("TK-108", 2, "Dr. Iyer", "Room 104"));

        queue.add(new Patient("TK-109", 4, "Dr. Rao", "Room 105"));
        queue.add(new Patient("TK-110", 1, "Dr. Rao", "Room 105"));

        queue.add(new Patient("TK-111", 3, "Dr. Chatterjee", "Room 106"));
        queue.add(new Patient("TK-112", 2, "Dr. Chatterjee", "Room 106"));

        queue.add(new Patient("TK-113", 4, "Dr. Khan", "Room 107"));
        queue.add(new Patient("TK-114", 3, "Dr. Khan", "Room 107"));

        queue.add(new Patient("TK-115", 2, "Dr. Gupta", "Room 108"));
        queue.add(new Patient("TK-116", 1, "Dr. Gupta", "Room 108"));
    }

    private static void startHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            File file = path.equals("/") || path.isEmpty() ? new File("index.html") : new File("." + path);

            if (!file.exists() || file.isDirectory()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            String contentType = "text/plain";
            if (file.getName().endsWith(".html")) contentType = "text/html";
            else if (file.getName().endsWith(".json")) contentType = "application/json";
            else if (file.getName().endsWith(".js")) contentType = "application/javascript";

            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(bytes);
            }

            Headers h = exchange.getResponseHeaders();
            h.set("Content-Type", contentType);
            h.set("Cache-Control", "no-cache, no-store, must-revalidate");
            h.set("Access-Control-Allow-Origin", "*");

            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }
}