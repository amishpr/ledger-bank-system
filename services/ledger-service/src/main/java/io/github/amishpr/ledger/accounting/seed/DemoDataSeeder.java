package io.github.amishpr.ledger.accounting.seed;

import io.github.amishpr.ledger.accounting.application.AccountService;
import io.github.amishpr.ledger.accounting.application.PostTransactionCommand;
import io.github.amishpr.ledger.accounting.application.PostingResult;
import io.github.amishpr.ledger.accounting.application.PostingService;
import io.github.amishpr.ledger.accounting.domain.Account;
import io.github.amishpr.ledger.accounting.domain.AccountType;
import io.github.amishpr.ledger.accounting.domain.EntryDirection;
import io.github.amishpr.ledger.accounting.domain.PostingLine;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Builds a year of believable activity for two fictional people, so the
 * dashboard has something to show: paychecks, groceries, rent, dining out,
 * subscriptions, a savings sweep, a few big purchases, and one reversal.
 *
 * <p>A port of the original {@code server/prisma/seed.ts}. It makes the same
 * random calls in the same order with the same seed, so it produces the same
 * year as the browser demo. Runs only when {@code ledger.seed.enabled=true}
 * and only into an empty ledger, so restarting a service never doubles it.
 *
 * <p>The recurring transfer the original script also created belongs to the
 * recurring transfer service now, which seeds it on its own side.
 */
@Component
@ConditionalOnBooleanProperty("ledger.seed.enabled")
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String[] DINING_SPOTS = {"Coffee shop", "Lunch out", "Dinner with friends", "Takeout", "Brunch", "Pizza night"};
    private static final String[] BIG_PURCHASES = {"Flight tickets", "New laptop", "Furniture", "Holiday shopping"};

    private final AccountService accounts;
    private final PostingService posting;
    private final Clock clock;
    private final ZoneId zone;

    DemoDataSeeder(
            AccountService accounts,
            PostingService posting,
            Clock clock,
            @Value("${ledger.seed.zone:#{T(java.time.ZoneId).systemDefault().id}}") String zone) {
        this.accounts = accounts;
        this.posting = posting;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    private record SeedEvent(ZonedDateTime date, String description, List<PostingLine> lines) {}

    @Override
    public void run(ApplicationArguments args) {
        if (!accounts.isEmpty()) {
            log.info("Ledger already has accounts, skipping the demo seed");
            return;
        }
        long started = System.nanoTime();
        int posted = seed();
        log.info("Seeded the demo ledger with {} transactions in {} ms", posted, (System.nanoTime() - started) / 1_000_000);
    }

    int seed() {
        Mulberry32 random = new Mulberry32(1337);

        Account equity = accounts.open("Opening Balance Equity", AccountType.EQUITY, "USD");
        Account alexChecking = accounts.open("Checking - Alex", AccountType.ASSET, "USD");
        Account alexSavings = accounts.open("Savings - Alex", AccountType.ASSET, "USD");
        Account jordanChecking = accounts.open("Checking - Jordan", AccountType.ASSET, "USD");
        Account paycheckRevenue = accounts.open("Revenue - Paycheck", AccountType.REVENUE, "USD");
        Account interestRevenue = accounts.open("Revenue - Interest", AccountType.REVENUE, "USD");
        Account feesExpense = accounts.open("Expenses - Bank Fees", AccountType.EXPENSE, "USD");
        Account diningExpense = accounts.open("Expenses - Dining", AccountType.EXPENSE, "USD");
        Account subscriptionsExpense = accounts.open("Expenses - Subscriptions", AccountType.EXPENSE, "USD");
        Account rentExpense = accounts.open("Expenses - Rent", AccountType.EXPENSE, "USD");
        Account groceriesExpense = accounts.open("Expenses - Groceries", AccountType.EXPENSE, "USD");

        ZonedDateTime today = ZonedDateTime.now(clock.withZone(zone));
        ZonedDateTime yearAgo = addDays(random, today, -365);

        int count = 0;
        postAt(yearAgo, "Opening balance", transfer(equity.getId(), alexChecking.getId(), 250_000));
        postAt(yearAgo, "Opening balance", transfer(equity.getId(), alexSavings.getId(), 1_000_000));
        postAt(yearAgo, "Opening balance", transfer(equity.getId(), jordanChecking.getId(), 80_000));
        count += 3;

        List<SeedEvent> events = new ArrayList<>();

        // Biweekly paycheck, starting a few days in.
        for (int day = 3; day < 365; day += 14) {
            events.add(new SeedEvent(addDays(random, yearAgo, day), "Paycheck deposit",
                    transfer(paycheckRevenue.getId(), alexChecking.getId(), 240_000)));
        }

        // Weekly groceries, plus dining out once a week and sometimes twice.
        // Each local is drawn in the same order the TypeScript evaluates its
        // arguments, which is what keeps the two sequences identical.
        for (int week = 0; week < 52; week++) {
            int groceryDay = week * 7 + random.nextInt(0, 6);
            long groceries = random.nextInt(6_500, 14_000);
            events.add(new SeedEvent(addDays(random, yearAgo, groceryDay), "Groceries",
                    transfer(alexChecking.getId(), groceriesExpense.getId(), groceries)));

            int diningDay = week * 7 + random.nextInt(0, 6);
            long dining = random.nextInt(800, 3_500);
            String spot = random.pick(DINING_SPOTS);
            events.add(new SeedEvent(addDays(random, yearAgo, diningDay), spot,
                    transfer(alexChecking.getId(), diningExpense.getId(), dining)));

            if (random.next() < 0.5) {
                int secondDay = week * 7 + random.nextInt(0, 6);
                long second = random.nextInt(1_500, 7_000);
                String secondSpot = random.pick(DINING_SPOTS);
                events.add(new SeedEvent(addDays(random, yearAgo, secondDay), secondSpot,
                        transfer(alexChecking.getId(), diningExpense.getId(), second)));
            }
        }

        // Monthly rent, fee, subscription, interest, a sweep to savings, and a
        // rent split with Jordan, spaced by real calendar months.
        for (int m = 1; m <= 12; m++) {
            ZonedDateTime monthDate = addMonths(random, yearAgo, m);
            events.add(new SeedEvent(monthDate, "Rent payment", transfer(alexChecking.getId(), rentExpense.getId(), 145_000)));
            events.add(new SeedEvent(monthDate, "Monthly maintenance fee", transfer(alexChecking.getId(), feesExpense.getId(), 250)));
            events.add(new SeedEvent(monthDate, "Streaming subscription", transfer(alexChecking.getId(), subscriptionsExpense.getId(), 1_599)));
            events.add(new SeedEvent(monthDate, "Interest earned",
                    transfer(interestRevenue.getId(), alexSavings.getId(), random.nextInt(2_500, 4_500))));
            events.add(new SeedEvent(monthDate, "Savings transfer",
                    transfer(alexChecking.getId(), alexSavings.getId(), random.nextInt(150_000, 210_000))));
            events.add(new SeedEvent(monthDate, "Rent split - Alex to Jordan",
                    transfer(alexChecking.getId(), jordanChecking.getId(), random.nextInt(11_500, 13_500))));
        }

        // A handful of large one-off purchases, through their own expense account.
        Account shoppingExpense = accounts.open("Expenses - Shopping", AccountType.EXPENSE, "USD");
        int[] bigPurchaseWeeks = {8, 20, 33, 45};
        for (int i = 0; i < bigPurchaseWeeks.length; i++) {
            long amount = random.nextInt(110_000, 250_000);
            int day = bigPurchaseWeeks[i] * 7 + random.nextInt(0, 6);
            events.add(new SeedEvent(addDays(random, yearAgo, day), BIG_PURCHASES[i],
                    transfer(alexChecking.getId(), shoppingExpense.getId(), amount)));
        }

        // A stable sort, like JavaScript's, so same-day events keep their order.
        events.sort(Comparator.comparing(e -> e.date().toInstant()));
        for (SeedEvent event : events) {
            posting.postBackdated(new PostTransactionCommand(event.description(), event.lines()), event.date().toInstant());
            count++;
        }

        posting.post(new PostTransactionCommand("Coffee shop", transfer(alexChecking.getId(), diningExpense.getId(), 875)));
        PostingResult interest = posting.post(
                new PostTransactionCommand("Interest earned", transfer(interestRevenue.getId(), alexSavings.getId(), 1_234)));
        posting.reverse(interest.transaction().getId(), "Correcting duplicate interest posting");
        return count + 3;
    }

    /** Money moves out of {@code from} (a credit) and into {@code to} (a debit). */
    private static List<PostingLine> transfer(UUID from, UUID to, long amountMinor) {
        return List.of(
                new PostingLine(to, EntryDirection.DEBIT, amountMinor),
                new PostingLine(from, EntryDirection.CREDIT, amountMinor));
    }

    private void postAt(ZonedDateTime date, String description, List<PostingLine> lines) {
        posting.postBackdated(new PostTransactionCommand(description, lines), date.toInstant());
    }

    /** {@code setDate(getDate() + days)} then a random time of day, as in the TypeScript. */
    private static ZonedDateTime addDays(Mulberry32 random, ZonedDateTime date, int days) {
        int hour = random.nextInt(8, 20);
        int minute = random.nextInt(0, 59);
        int second = random.nextInt(0, 59);
        return date.plusDays(days).withHour(hour).withMinute(minute).withSecond(second).withNano(0);
    }

    /**
     * JavaScript's {@code setMonth} lets the day overflow (Jan 31 plus one month
     * is Mar 3), where Java clamps to Feb 28. Matching JavaScript here keeps
     * the seeded dates identical to the browser demo's.
     */
    private static ZonedDateTime addMonths(Mulberry32 random, ZonedDateTime date, int months) {
        int hour = random.nextInt(8, 20);
        int minute = random.nextInt(0, 59);
        ZonedDateTime overflowed = date.withDayOfMonth(1).plusMonths(months).plusDays(date.getDayOfMonth() - 1L);
        return overflowed.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }
}
