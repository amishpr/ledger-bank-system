package io.github.amishpr.ledger.accounting.application;

import io.github.amishpr.ledger.accounting.domain.PostingLine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Fingerprints a posting request so a reused idempotency key can be told
 * apart from a genuine retry. Entry order does not matter, since the same
 * transaction written in a different order is still the same transaction.
 */
final class RequestHasher {

    private static final Comparator<PostingLine> CANONICAL = Comparator
            .comparing((PostingLine l) -> l.accountId().toString())
            .thenComparing(l -> l.direction().name())
            .thenComparingLong(PostingLine::amountMinor);

    private RequestHasher() {}

    static String hash(String description, List<PostingLine> lines) {
        StringBuilder canonical = new StringBuilder(description.length() + lines.size() * 64);
        canonical.append(description.length()).append(':').append(description);
        lines.stream().sorted(CANONICAL).forEach(line -> canonical
                .append('|').append(line.accountId())
                .append(',').append(line.direction())
                .append(',').append(line.amountMinor()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
