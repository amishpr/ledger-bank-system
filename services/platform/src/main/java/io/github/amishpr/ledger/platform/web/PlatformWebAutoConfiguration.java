package io.github.amishpr.ledger.platform.web;

import io.github.amishpr.ledger.platform.PlatformAutoConfiguration;
import io.github.amishpr.ledger.platform.tracing.CurrentTraceId;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Problem Details error handling for every servlet based service. */
@AutoConfiguration(after = PlatformAutoConfiguration.class, before = WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(ResponseEntityExceptionHandler.class)
public class PlatformWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)
    ProblemDetailsExceptionHandler problemDetailsExceptionHandler(CurrentTraceId currentTraceId) {
        return new ProblemDetailsExceptionHandler(currentTraceId);
    }
}
