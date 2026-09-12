package com.sanlam.banking.withdrawal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accepts an inbound X-Correlation-Id or mints one, puts it in the MDC so every log line
 * for this request carries it, echoes it back, and clears it afterwards - without the
 * finally block the value leaks to whatever the pooled thread handles next.
 *
 * <p>An inbound value is only adopted if it fits the shape below. It is caller-supplied
 * text that ends up in every log line for the request and persisted on the outbox row, so
 * it needs a bound and a character set rather than whatever arrives. A value that does not
 * fit is replaced rather than rejected: correlation is a diagnostic aid, and failing a
 * withdrawal over a malformed trace header would be the wrong trade.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER  = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Wide enough for a UUID, a W3C trace id or a caller's own reference. */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (!StringUtils.hasText(correlationId) || !ACCEPTABLE.matcher(correlationId).matches()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
