package io.github.amishpr.ledger.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amishpr.ledger.events.EntrySnapshot;
import io.github.amishpr.ledger.events.Origin;
import io.github.amishpr.ledger.events.RecurringTransferExecuted;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.events.TransactionPosted;
import io.github.amishpr.ledger.events.TransactionSnapshot;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {Topics.TRANSACTIONS, Topics.RECURRING_TRANSFERS})
class NotificationIntegrationTest {

    @LocalServerPort int port;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired IntegrationEventCodec codec;
    @Autowired KafkaListenerEndpointRegistry listeners;

    /** Collects every text frame a socket receives. */
    static final class Inbox implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            socket.request(1);
            return null;
        }

        String next() throws InterruptedException {
            String message = messages.poll(15, TimeUnit.SECONDS);
            assertThat(message).as("a message within 15 seconds").isNotNull();
            return message;
        }
    }

    private WebSocket connect(Inbox inbox, String origin) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", origin)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), inbox)
                .join();
    }

    private TransactionSnapshot transfer(String description, Origin origin) {
        UUID id = UUID.randomUUID();
        Instant at = Instant.now();
        return new TransactionSnapshot(id, description, "POSTED", null, at, origin, List.of(
                new EntrySnapshot(UUID.randomUUID(), id, UUID.randomUUID(), "Savings", "ASSET", "DEBIT", 1_000, at),
                new EntrySnapshot(UUID.randomUUID(), id, UUID.randomUUID(), "Checking", "ASSET", "CREDIT", 1_000, at)));
    }

    @Test
    void pushesLedgerEventsToEveryConnectedDashboard() throws Exception {
        ContainerTestUtils.waitForAssignment(listeners.getListenerContainer("browser-fanout"), 2);
        Inbox first = new Inbox();
        Inbox second = new Inbox();
        WebSocket a = connect(first, "http://localhost:5173");
        WebSocket b = connect(second, "http://localhost:5173");
        assertThat(first.next()).isEqualTo("{\"type\":\"connected\"}");
        assertThat(second.next()).isEqualTo("{\"type\":\"connected\"}");

        TransactionSnapshot manual = transfer("Rent split", null);
        kafka.send(Topics.TRANSACTIONS, manual.id().toString(), codec.write(TransactionPosted.of(manual, List.of(), Instant.now()))).get();

        // Posted by the scheduler: the ledger's event is held back, the scheduler's is sent.
        TransactionSnapshot scheduled = transfer("Savings sweep", new Origin(Origin.RECURRING_TRANSFER, "s-1"));
        kafka.send(Topics.TRANSACTIONS, scheduled.id().toString(), codec.write(TransactionPosted.of(scheduled, List.of(), Instant.now()))).get();
        kafka.send(Topics.RECURRING_TRANSFERS, "s-1", codec.write(
                RecurringTransferExecuted.of(UUID.randomUUID(), scheduled, List.of(), Instant.now()))).get();

        for (Inbox inbox : List.of(first, second)) {
            assertThat(inbox.next()).contains("\"type\":\"transaction.posted\"").contains("Rent split");
            assertThat(inbox.next()).contains("\"type\":\"recurring.executed\"").contains("Savings sweep");
            assertThat(inbox.messages.poll(1, TimeUnit.SECONDS)).isNull();
        }
        a.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        b.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void refusesAHandshakeFromAnUnknownOrigin() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var attempt = client.newWebSocketBuilder()
                .header("Origin", "https://evil.example")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new Inbox());

        assertThat(attempt).failsWithin(Duration.ofSeconds(10));
    }
}
