import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.util.*;

// Class to store group member details
class Member {
    String name;
    double netBalance; // Positive means they get back money, negative means they owe

    public Member(String name) {
        this.name = name;
        this.netBalance = 0.0;
    }
}

// Class to store individual expense details for analytics
class ExpenseLog {
    String description;
    double amount;
    String payer;
    String category;

    public ExpenseLog(String description, double amount, String payer, String category) {
        this.description = description;
        this.amount = amount;
        this.payer = payer;
        this.category = category;
    }
}

public class Main {
    private Map<String, Member> groupMembers = new HashMap<>();
    private List<ExpenseLog> expenseHistory = new ArrayList<>();
    private List<String> paymentHistory = new ArrayList<>();
    
    private double totalExpensesIncurred = 0.0;

    private void startServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 9000), 0);
        server.createContext("/api/", this::handleApiRequest);
        server.createContext("/", this::serveIndex);
        server.start();
        System.out.println("Campus Splitter is running at http://localhost:9000");
    }

    private void serveIndex(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())
                && !"/index.html".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] page = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("index.html"));
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, page.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(page);
        }
    }

    private void handleApiRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            if ("GET".equals(exchange.getRequestMethod()) && "/api/state".equals(path)) {
                sendJson(exchange, 200, stateJson());
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            Map<String, String> form = readForm(exchange);
            if ("/api/members".equals(path)) {
                addMembers(form.getOrDefault("names", ""));
            } else if ("/api/expenses".equals(path)) {
                String description = form.getOrDefault("description", "").trim();
                String payer = form.getOrDefault("payer", "").trim();
                String category = form.getOrDefault("category", "General").trim();
                double amount = parseAmount(form.get("amount"));
                if (description.isEmpty() || category.isEmpty()) {
                    throw new IllegalArgumentException("Enter a description and category.");
                }
                if (!groupMembers.containsKey(payer)) {
                    throw new IllegalArgumentException("Select a payer who is in the group.");
                }
                Set<String> participants = new HashSet<>();
                String names = form.getOrDefault("participants", "").trim();
                if (!names.isEmpty()) {
                    for (String name : names.split(",")) {
                        String participant = name.trim();
                        if (!groupMembers.containsKey(participant)) {
                            throw new IllegalArgumentException("Unknown participant: " + participant);
                        }
                        participants.add(participant);
                    }
                } else {
                    participants.addAll(groupMembers.keySet());
                }
                recordExpense(description, amount, payer, category, participants);
            } else if ("/api/payments".equals(path)) {
                String from = form.getOrDefault("from", "").trim();
                String to = form.getOrDefault("to", "").trim();
                double amount = parseAmount(form.get("amount"));
                if (from.equals(to)) {
                    throw new IllegalArgumentException("Choose two different group members.");
                }
                if (!groupMembers.containsKey(from) || !groupMembers.containsKey(to)) {
                    throw new IllegalArgumentException("Both people must be in the group.");
                }
                recordCashHandover(from, to, amount);
            } else if ("/api/settlements".equals(path)) {
                sendJson(exchange, 200, settlementsJson());
                return;
            } else {
                sendJson(exchange, 404, "{\"error\":\"Not found\"}");
                return;
            }
            sendJson(exchange, 200, stateJson());
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, "{\"error\":\"" + jsonEscape(error.getMessage()) + "\"}");
        } catch (Exception error) {
            sendJson(exchange, 500, "{\"error\":\"Request could not be completed.\"}");
            error.printStackTrace();
        }
    }

    private double parseAmount(String value) {
        try {
            double amount = Double.parseDouble(value);
            if (amount <= 0 || !Double.isFinite(amount)) {
                throw new IllegalArgumentException("Amount must be greater than zero.");
            }
            return amount;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Enter a valid amount.");
        }
    }

    private Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8);
            String value = parts.length > 1
                    ? URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private String stateJson() {
        StringBuilder json = new StringBuilder("{\"members\":[");
        boolean first = true;
        for (Member member : groupMembers.values()) {
            if (!first) json.append(',');
            json.append("{\"name\":\"").append(jsonEscape(member.name))
                    .append("\",\"balance\":").append(member.netBalance).append('}');
            first = false;
        }
        json.append("],\"expenses\":[");
        first = true;
        for (ExpenseLog expense : expenseHistory) {
            if (!first) json.append(',');
            json.append("{\"description\":\"").append(jsonEscape(expense.description))
                    .append("\",\"amount\":").append(expense.amount)
                    .append(",\"payer\":\"").append(jsonEscape(expense.payer))
                    .append("\",\"category\":\"").append(jsonEscape(expense.category)).append("\"}");
            first = false;
        }
        json.append("],\"payments\":[");
        first = true;
        for (String payment : paymentHistory) {
            if (!first) json.append(',');
            json.append('"').append(jsonEscape(payment)).append('"');
            first = false;
        }
        return json.append("],\"total\":").append(totalExpensesIncurred).append('}').toString();
    }

    private String settlementsJson() {
        List<Member> debtors = new ArrayList<>();
        List<Member> creditors = new ArrayList<>();
        for (Member member : groupMembers.values()) {
            Member copy = new Member(member.name);
            copy.netBalance = member.netBalance;
            if (copy.netBalance < -0.01) debtors.add(copy);
            else if (copy.netBalance > 0.01) creditors.add(copy);
        }
        debtors.sort((a, b) -> Double.compare(a.netBalance, b.netBalance));
        creditors.sort((a, b) -> Double.compare(b.netBalance, a.netBalance));

        StringBuilder json = new StringBuilder("[ ");
        int debtorIndex = 0;
        int creditorIndex = 0;
        boolean first = true;
        while (debtorIndex < debtors.size() && creditorIndex < creditors.size()) {
            Member debtor = debtors.get(debtorIndex);
            Member creditor = creditors.get(creditorIndex);
            double amount = Math.min(Math.abs(debtor.netBalance), creditor.netBalance);
            if (!first) json.append(',');
            json.append("{\"debtor\":\"").append(jsonEscape(debtor.name))
                    .append("\",\"creditor\":\"").append(jsonEscape(creditor.name))
                    .append("\",\"amount\":").append(amount).append('}');
            first = false;
            debtor.netBalance += amount;
            creditor.netBalance -= amount;
            if (Math.abs(debtor.netBalance) < 0.01) debtorIndex++;
            if (Math.abs(creditor.netBalance) < 0.01) creditorIndex++;
        }
        return json.append(" ]").toString();
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // FEATURE 1: Add Multiple Members At Once
    public void addMembers(String inputNames) {
        String[] names = inputNames.split(",");
        int addedCount = 0;
        for (String name : names) {
            String trimmedName = name.trim();
            if (!trimmedName.isEmpty() && !groupMembers.containsKey(trimmedName)) {
                groupMembers.put(trimmedName, new Member(trimmedName));
                addedCount++;
            }
        }
        System.out.println("✨ Successfully added " + addedCount + " new friend(s) to the group!");
    }

    // FEATURE 2: Split a New Bill
    public void recordExpense(String desc, double totalAmount, String payer, String category, Set<String> participants) {
        if (!groupMembers.containsKey(payer)) {
            System.out.println("❌ Oops! Payer name not found. Add them as a member first.");
            return;
        }

        participants.add(payer); // The payer automatically gets their fair split share
        double sharePerHead = totalAmount / participants.size();

        // Update active balances
        groupMembers.get(payer).netBalance += totalAmount;
        for (String name : participants) {
            if (groupMembers.containsKey(name)) {
                groupMembers.get(name).netBalance -= sharePerHead;
            }
        }

        // Log the details for dashboard history
        expenseHistory.add(new ExpenseLog(desc, totalAmount, payer, category));
        totalExpensesIncurred += totalAmount;

        System.out.println("🎉 Bill logged! Split equally among " + participants.size() + " people.");
    }

    // FEATURE 3: Friendly Debt Settlement Engine ("Who Pays Whom")
    public void calculateWhoPaysWhom() {
        System.out.println("\n💡 ===== WHO PAYS WHOM TO SETTLE UP =====");
        
        List<Member> debtors = new ArrayList<>();
        List<Member> creditors = new ArrayList<>();

        // Deep copy balances into temporary spaces to run simulation safely
        for (Member m : groupMembers.values()) {
            Member temp = new Member(m.name);
            temp.netBalance = m.netBalance;
            if (temp.netBalance < -0.01) debtors.add(temp);
            else if (temp.netBalance > 0.01) creditors.add(temp);
        }

        // Sort to match highest debtors with highest creditors
        debtors.sort((a, b) -> Double.compare(a.netBalance, b.netBalance));
        creditors.sort((a, b) -> Double.compare(b.netBalance, a.netBalance));

        int dIdx = 0, cIdx = 0;
        boolean settlementsFound = false;

        while (dIdx < debtors.size() && cIdx < creditors.size()) {
            Member debtor = debtors.get(dIdx);
            Member creditor = creditors.get(cIdx);

            double amountOwed = Math.abs(debtor.netBalance);
            double amountCredited = creditor.netBalance;
            double settledAmount = Math.min(amountOwed, amountCredited);

            if (settledAmount > 0.01) {
                System.out.printf("🤝 %s should give %.2f INR to %s\n", debtor.name, settledAmount, creditor.name);
                settlementsFound = true;
            }

            debtor.netBalance += settledAmount;
            creditor.netBalance -= settledAmount;

            if (Math.abs(debtor.netBalance) < 0.01) dIdx++;
            if (Math.abs(creditor.netBalance) < 0.01) cIdx++;
        }

        if (!settlementsFound) {
            System.out.println("🥳 Awesome! Everyone is perfectly squared up. No one owes anything!");
        }
    }

    // FEATURE 4: Log a Cash Handover Payment
    public void recordCashHandover(String fromPerson, String toPerson, double amount) {
        if (groupMembers.containsKey(fromPerson) && groupMembers.containsKey(toPerson)) {
            groupMembers.get(fromPerson).netBalance += amount;
            groupMembers.get(toPerson).netBalance -= amount;
            
            String logEntry = String.format("💸 [CASH HANDOVER] %s paid %.2f INR directly to %s", fromPerson, amount, toPerson);
            paymentHistory.add(logEntry);
            System.out.println("✅ Cash handover successfully recorded.");
        } else {
            System.out.println("❌ Hmmm, one or both of those names don't exist in the group.");
        }
    }

    // ADVANCED FEATURE 5: Clean, Simplified Analytics Dashboard
    @SuppressWarnings("unchecked")
    public void displayDashboardStatistics() {
        System.out.println("\n========================================================");
        System.out.println("             📊 GROUP SPENDING DASHBOARD               ");
        System.out.println("========================================================");
        System.out.printf("🔹 Total Group Spending So Far: %.2f INR\n", totalExpensesIncurred);
        
        if (expenseHistory.isEmpty()) {
            System.out.println("📌 No items logged yet. Try adding a bill first!");
            return;
        }

        // --- 1. HIGHEST TO LOWEST SPENDER ANALYTICS ---
        System.out.println("\n🏆 SPENDING LEADERBOARD (Who funded the most upfront):");
        Map<String, Double> userSpends = new HashMap<>();
        for (String name : groupMembers.keySet()) {
            userSpends.put(name, 0.0);
        }
        for (ExpenseLog e : expenseHistory) {
            userSpends.put(e.payer, userSpends.get(e.payer) + e.amount);
        }
        
        List<Map.Entry<String, Double>> sortedSpenders = new ArrayList<>(userSpends.entrySet());
        sortedSpenders.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int rank = 1;
        for (Map.Entry<String, Double> entry : sortedSpenders) {
            String crown = (rank == 1 && entry.getValue() > 0) ? "👑 " : "   ";
            System.out.printf("%s#%d %s - Contributed: %.2f INR\n", crown, rank++, entry.getKey(), entry.getValue());
        }

        // --- 2. EXPENSES BY CATEGORY BREAKDOWN ---
        System.out.println("\n📂 SPENDING BY CATEGORY:");
        Map<String, Double> categoryTotals = new HashMap<>();
        for (ExpenseLog e : expenseHistory) {
            categoryTotals.put(e.category, categoryTotals.getOrDefault(e.category, 0.0) + e.amount);
        }
        
        for (Map.Entry<String, Double> entry : categoryTotals.entrySet()) {
            double sharePercentage = (entry.getValue() / totalExpensesIncurred) * 100;
            System.out.printf("   ▪️ %-10s : %.2f INR (%.1f%% of total group budget)\n", 
                    entry.getKey().toUpperCase(), entry.getValue(), sharePercentage);
        }
    }

    // FEATURE 6: Friendly Historical Logs
    public void showPastLogs() {
        System.out.println("\n📜 --- PAST BILLS LOG ---");
        if (expenseHistory.isEmpty()) System.out.println("No bills have been entered yet.");
        for (ExpenseLog e : expenseHistory) {
            System.out.printf(" 🛍️  [%s] %s paid %.2f INR for '%s'\n", e.category.toUpperCase(), e.payer, e.amount, e.description);
        }

        System.out.println("\n📜 --- CASH HANDOVER HISTORY ---");
        if (paymentHistory.isEmpty()) System.out.println("No cash handovers recorded yet.");
        for (String log : paymentHistory) {
            System.out.println(log);
        }
    }

    public static void main(String[] args) {
        Main app = new Main();
        if (args.length > 0 && "--server".equals(args[0])) {
            try {
                app.startServer();
            } catch (IOException error) {
                System.err.println("Could not start server: " + error.getMessage());
            }
            return;
        }
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("====================================================");
        System.out.println("      🤝 FRIENDLY CAMPUS BILL SPLITTER SUITE        ");
        System.out.println("====================================================");

        while (true) {
            System.out.println("\n-------------------- WHAT NEXT? --------------------");
            System.out.println("1. Add Friends to Group (Single or Multiple)");
            System.out.println("2. Split a New Bill / Expense");
            System.out.println("3. Check 'Who Pays Whom' Right Now");
            System.out.println("4. Record a Direct Cash Handover");
            System.out.println("5. View Spending Leaderboard & Category Charts");
            System.out.println("6. View Past Bills & Cash Handover History");
            System.out.println("7. Close Application");
            System.out.print("Choose an option (1-7): ");
            
            int choice = scanner.nextInt();
            scanner.nextLine(); // clear buffer

            switch (choice) {
                case 1:
                    System.out.print("Enter names (separated by commas to add multiple at once, e.g., Amit,Rahul,Sneha): ");
                    app.addMembers(scanner.nextLine());
                    break;

                case 2:
                    System.out.print("What was this bill for? (e.g., Pizza Party): ");
                    String desc = scanner.nextLine();
                    System.out.print("Enter total bill amount: ");
                    double amt = scanner.nextDouble();
                    scanner.nextLine();
                    System.out.print("Who paid it upfront? ");
String payer = scanner.nextLine().trim();
System.out.print("Enter Category (e.g., Food, Travel, Books): ");
String cat = scanner.nextLine().trim();
System.out.print("Enter OTHER friends sharing this bill (separated by commas, or leave blank if everyone splits): ");
String inputNames = scanner.nextLine();
Set participants = new HashSet<>();
if (!inputNames.trim().isEmpty()) {
for (String n : inputNames.split(",")) participants.add(n.trim());
}
app.recordExpense(desc, amt, payer, cat, participants);
break;
case 3:
System.out.println("\n===== CURRENT BALANCE STANDINGS =====");
app.groupMembers.values().forEach(m -> System.out.printf("   %-8s: Balance = %.2f INR\n", m.name, m.netBalance));
app.calculateWhoPaysWhom();
break;
case 4:
System.out.print("Who is handing over the cash? ");
String deb = scanner.nextLine().trim();
System.out.print("Who is receiving the cash? ");
String cred = scanner.nextLine().trim();
System.out.print("Enter amount transferred: ");
double payAmt = scanner.nextDouble();
app.recordCashHandover(deb, cred, payAmt);
break;
case 5:
app.displayDashboardStatistics();
break;
case 6:
app.showPastLogs();
break;
case 7:
System.out.println("👋 See ya! Closing the split suite.");
scanner.close();
System.exit(0);
default:
System.out.println("❌ Invalid choice. Pick a number between 1 and 7!");
}
}
}
}
