import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.util.*;

class Member {
    String name;
    double netBalance;
    double totalContributed;

    public Member(String name) {
        this.name = name;
        this.netBalance = 0.0;
        this.totalContributed = 0.0;
    }
}

public class Main {
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Map<String, Double> categoryTotals = new HashMap<>();
    private final List<String> billLogs = new ArrayList<>();
    private final List<String> handoverLogs = new ArrayList<>();
    private double totalExpenses = 0.0;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 9005), 0);
        Main app = new Main();
        server.createContext("/api/action", app::handleAction);
        server.createContext("/", app::serveIndex);
        server.start();
        System.out.println("🚀 Backend Engine Running at http://localhost:9005");
    }

    private void serveIndex(HttpExchange ex) throws IOException {
        byte[] page = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("index.html"));
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, page.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(page); }
    }

    private void handleAction(HttpExchange ex) throws IOException {
        Map<String, String> params = readForm(ex);
        String action = params.getOrDefault("action", "");
        String responseText = "";

        try {
            switch (action) {
                case "addMembers":
                    String[] names = params.getOrDefault("names", "").split(",");
                    for (String n : names) {
                        String name = n.trim();
                        if (!name.isEmpty()) members.putIfAbsent(name, new Member(name));
                    }
                    responseText = "✨ Group updated successfully!";
                    break;

                case "splitBill":
                    String desc = params.getOrDefault("desc", "").trim();
                    double amt = Double.parseDouble(params.get("amount"));
                    String payer = params.getOrDefault("payer", "").trim();
                    String cat = params.getOrDefault("category", "General").trim().toUpperCase();
                    String shared = params.getOrDefault("sharedWith", "").trim();

                    if (!members.containsKey(payer)) throw new IllegalArgumentException("Payer not found.");

                    Set<String> parts = new HashSet<>();
                    if (!shared.isEmpty()) {
                        for (String s : shared.split(",")) if (members.containsKey(s.trim())) parts.add(s.trim());
                    } else {
                        parts.addAll(members.keySet());
                    }
                    parts.add(payer);

                    // Core Java Business Logic Calculations
                    double share = amt / parts.size();
                    members.get(payer).netBalance += amt;
                    members.get(payer).totalContributed += amt;
                    for (String p : parts) {
                        members.get(p).netBalance -= share;
                    }

                    totalExpenses += amt;
                    categoryTotals.put(cat, categoryTotals.getOrDefault(cat, 0.0) + amt);
                    billLogs.add(String.format("🛍️ [%s] %s paid %.2f INR for '%s'", cat, payer, amt, desc));
                    responseText = "✅ Bill split processed by Java Engine.";
                    break;

                case "handover":
                    String from = params.getOrDefault("from", "").trim();
                    String to = params.getOrDefault("to", "").trim();
                    double hoAmt = Double.parseDouble(params.get("amount"));

                    if (members.containsKey(from) && members.containsKey(to)) {
                        members.get(from).netBalance += hoAmt;
                        members.get(to).netBalance -= hoAmt;
                        handoverLogs.add(String.format("💸 %s paid %.2f INR directly to %s", from, hoAmt, to));
                        responseText = "✅ Cash handover balanced successfully.";
                    }
                    break;

                case "getUIState":
                    responseText = buildUIStateResponse();
                    break;
            }
            sendResponse(ex, 200, responseText);
        } catch (Exception e) {
            sendResponse(ex, 400, "❌ Error: " + e.getMessage());
        }
    }

    // Processing dynamic UI state inside Java using Collections Framework
    private String buildUIStateResponse() {
        StringBuilder sb = new StringBuilder();

        // 1. Current Standings & Balanced Clearance Calculations
        sb.append("<h3>👥 Current Balances</h3><ul>");
        members.values().forEach(m -> sb.append(String.format("<li>%s: Balance = %.2f INR</li>", m.name, m.netBalance)));
        sb.append("</ul>");

        sb.append("<h3>🤝 Who Pays Whom</h3><ul>");
        List<Member> debtors = new ArrayList<>();
        List<Member> creditors = new ArrayList<>();
        for (Member m : members.values()) {
            Member copy = new Member(m.name); copy.netBalance = m.netBalance;
            if (copy.netBalance < -0.01) debtors.add(copy);
            else if (copy.netBalance > 0.01) creditors.add(copy);
        }
        debtors.sort(Comparator.comparingDouble(a -> a.netBalance));
        creditors.sort((a, b) -> Double.compare(b.netBalance, a.netBalance));

        int i = 0, j = 0; boolean found = false;
        while (i < debtors.size() && j < creditors.size()) {
            Member d = debtors.get(i); Member c = creditors.get(j);
            double settle = Math.min(Math.abs(d.netBalance), c.netBalance);
            if (settle > 0.01) {
                sb.append(String.format("<li>👉 <b>%s</b> should pay <b>%.2f INR</b> to <b>%s</b></li>", d.name, settle, c.name));
                found = true;
            }
            d.netBalance += settle; c.netBalance -= settle;
            if (Math.abs(d.netBalance) < 0.01) i++;
            if (Math.abs(c.netBalance) < 0.01) j++;
        }
        if (!found) sb.append("<li>🥳 Everyone is perfectly squared up!</li>");
        sb.append("</ul>");

        // 2. Spending Leaderboard Analytics Engine
        sb.append("<h3>🏆 Spending Leaderboard</h3><ul>");
        List<Member> sortedSpenders = new ArrayList<>(members.values());
        sortedSpenders.sort((a, b) -> Double.compare(b.totalContributed, a.totalContributed));
        int rank = 1;
        for (Member m : sortedSpenders) {
            String icon = (rank == 1 && m.totalContributed > 0) ? "👑 " : "▪️ ";
            sb.append(String.format("<li>%s#%d %s - Contributed: %.2f INR</li>", icon, rank++, m.name, m.totalContributed));
        }
        sb.append("</ul>");

        // 3. Category Breakdown Metrics
        sb.append("<h3>📂 Spending By Category</h3><ul>");
        categoryTotals.forEach((cat, amt) -> sb.append(String.format("<li>%s: %.2f INR</li>", cat, amt)));
        sb.append("</ul>");

        // 4. Audit History Streams
        sb.append("<h3>📜 Past Log Sheets</h3><strong>Bills:</strong><ul>");
        billLogs.forEach(log -> sb.append("<li>").append(log).append("</li>"));
        sb.append("</ul><strong>Handovers:</strong><ul>");
        handoverLogs.forEach(log -> sb.append("<li>").append(log).append("</li>"));
        sb.append("</ul>");

        return sb.toString();
    }

    private Map<String, String> readForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) map.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return map;
    }

    private void sendResponse(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
