package io.github.amishpr.ledger.insights;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights")
@Tag(name = "Insights", description = "Reports built from ledger events")
class InsightsController {

    record CategorySpending(
            UUID accountId,
            String accountName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "523400") long totalMinor) {}

    record MonthSpending(
            @Schema(example = "2026-09") String month,
            @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "381250") long totalMinor) {}

    record SpendingBreakdown(List<CategorySpending> byCategory, List<MonthSpending> byMonth) {}

    private final SpendingQueries queries;

    InsightsController(SpendingQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/spending")
    @Operation(
            summary = "Total spending by expense account and by month",
            description = """
                    Built from ledger events, so it is eventually consistent: a transaction shows up here a \
                    moment after the ledger accepts it.""")
    SpendingBreakdown spending() {
        return new SpendingBreakdown(
                queries.byCategory().stream()
                        .map(c -> new CategorySpending(c.accountId(), c.accountName(), c.totalMinor()))
                        .toList(),
                queries.byMonth().stream().map(m -> new MonthSpending(m.month(), m.totalMinor())).toList());
    }
}
