package com.bank.ledger.filter;

import com.bank.ledger.domain.IdempotencyRecord;
import com.bank.ledger.exception.BankingException;
import com.bank.ledger.service.IdempotencyService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyFilter implements Filter {

    private final IdempotencyService idempotencyService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        if (!(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        // Only enforce idempotency on POST requests that target transactions
        String method = httpRequest.getMethod();
        String path = httpRequest.getRequestURI();

        if (!"POST".equalsIgnoreCase(method) || !path.contains("/api/transactions")) {
            chain.doFilter(httpRequest, httpResponse);
            return;
        }

        String key = httpRequest.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            // Require Idempotency-Key for all transaction operations
            httpResponse.setStatus(HttpStatus.BAD_REQUEST.value());
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\": \"Missing-Idempotency-Key\", \"message\": \"Header 'Idempotency-Key' is required for write operations.\"}");
            return;
        }

        try {
            boolean isNew = idempotencyService.startProcessing(key);
            
            if (isNew) {
                // Wrap response to cache its output body
                ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpResponse);
                try {
                    chain.doFilter(httpRequest, responseWrapper);
                    
                    int status = responseWrapper.getStatus();
                    byte[] responseArray = responseWrapper.getContentAsByteArray();
                    String responseBody = new String(responseArray, StandardCharsets.UTF_8);
                    
                    // Cache the response if it was processed without system-level crashes
                    if (status != HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
                        idempotencyService.completeResponse(key, status, responseBody);
                    }
                    
                    responseWrapper.copyBodyToResponse();
                } catch (Exception e) {
                    // Cleanup processing status on server exception so it can be retried
                    log.error("Error occurred while processing idempotent request key={}", key, e);
                    throw e;
                }
            } else {
                // Duplicate request that was already completed - return cached response
                Optional<IdempotencyRecord> recordOpt = idempotencyService.getRecord(key);
                if (recordOpt.isPresent()) {
                    IdempotencyRecord record = recordOpt.get();
                    log.info("Returning cached response for idempotency key: {}", key);
                    httpResponse.setStatus(record.getResponseCode());
                    httpResponse.setContentType("application/json");
                    httpResponse.getWriter().write(record.getResponseBody());
                } else {
                    // Record missing or in invalid state (should not occur since startProcessing returned false)
                    httpResponse.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
                    httpResponse.setContentType("application/json");
                    httpResponse.getWriter().write("{\"error\": \"Idempotency-Error\", \"message\": \"Unable to fetch cached response.\"}");
                }
            }
        } catch (BankingException e) {
            httpResponse.setStatus(e.getStatus().value());
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write(String.format("{\"error\": \"%s\", \"message\": \"%s\"}", e.getClass().getSimpleName(), e.getMessage()));
        } catch (Exception e) {
            httpResponse.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\": \"ServerError\", \"message\": \"An unexpected error occurred during request processing.\"}");
        }
    }
}
