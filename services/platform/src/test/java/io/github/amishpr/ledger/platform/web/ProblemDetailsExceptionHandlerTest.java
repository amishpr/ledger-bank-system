package io.github.amishpr.ledger.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.amishpr.ledger.platform.json.MinorUnits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest
@Import(ProblemDetailsExceptionHandlerTest.ProbeController.class)
class ProblemDetailsExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @RestController
    static class ProbeController {

        record Payment(@NotBlank String description, MinorUnits amountMinor) {}

        @PostMapping("/probe/payments")
        Payment create(@Valid @RequestBody Payment payment) {
            return payment;
        }

        @GetMapping("/probe/things/{id}")
        String thing(@PathVariable UUID id, @RequestParam(defaultValue = "10") @Max(100) int limit) {
            return id + ":" + limit;
        }

        @GetMapping("/probe/broke")
        String broke() {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Account abc has insufficient funds");
        }

        @GetMapping("/probe/crash")
        String crash() {
            throw new IllegalStateException("database password is hunter2");
        }
    }

    @Test
    void mapsAnApiExceptionToItsStatusAndCode() throws Exception {
        mvc.perform(get("/probe/broke"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.detail").value("Account abc has insufficient funds"))
                .andExpect(jsonPath("$.instance").value("/probe/broke"));
    }

    @Test
    void listsEveryInvalidField() throws Exception {
        mvc.perform(post("/probe/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"\",\"amountMinor\":\"100\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("description"));
    }

    @Test
    void explainsABadAmountWithoutLeakingParserInternals() throws Exception {
        mvc.perform(post("/probe/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Lunch\",\"amountMinor\":10.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("amountMinor"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("whole number of cents")));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/probe/payments").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsAMalformedPathVariable() throws Exception {
        mvc.perform(get("/probe/things/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("id"));
    }

    @Test
    void validatesRequestParameters() throws Exception {
        mvc.perform(get("/probe/things/" + UUID.randomUUID()).param("limit", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("limit"));
    }

    @Test
    void hidesTheDetailsOfAnUnexpectedError() throws Exception {
        mvc.perform(get("/probe/crash"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Something went wrong on our side"));
    }

    @Test
    void givesFrameworkErrorsACodeToo() throws Exception {
        mvc.perform(post("/probe/broke"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/probe/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
