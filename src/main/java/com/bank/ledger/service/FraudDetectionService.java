package com.bank.ledger.service;

import com.bank.ledger.domain.Account;
import com.bank.ledger.exception.FraudException;
import com.bank.ledger.exception.LimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionService {

    private final StringRedisTemplate redisTemplate;

    private static final BigDecimal MAX_TRANSACTION_LIMIT = new BigDecimal("10000.0000"); // $10,000
    private static final int MAX_VELOCITY_LIMIT = 3; // Max 3 transactions...
    private static final long VELOCITY_WINDOW_SECONDS = 60; // ...per 60 seconds

    /**
     * Validates that single transaction limits are not violated.
     */
    public void validateTransactionLimits(Account account, BigDecimal amount) {
        // Internal reserve accounts are exempt from limits
        if (account.getAccountNumber().equals("BANK_CASH_RESERVE")) {
            return;
        }

        if (amount.compareTo(MAX_TRANSACTION_LIMIT) > 0) {
            log.warn("Account {} exceeded single transaction limit of {} with transaction of {}", 
                account.getAccountNumber(), MAX_TRANSACTION_LIMIT, amount);
            throw new LimitExceededException(
                "Transaction amount $" + amount + " exceeds single transaction limit of $" + MAX_TRANSACTION_LIMIT
            );
        }
    }

    /**
     * Checks velocity limits in real-time using a Redis Sorted Set (ZSET).
     * Implements a precise sliding window rate limiter.
     */
    public void checkVelocityLimit(Account account) {
        // Internal reserve accounts are exempt
        if (account.getAccountNumber().equals("BANK_CASH_RESERVE")) {
            return;
        }

        String key = "velocity:" + account.getAccountNumber();
        long now = Instant.now().toEpochMilli();
        long windowStart = now - (VELOCITY_WINDOW_SECONDS * 1000);

        try {
            // 1. Remove entries older than the sliding window start
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

            // 2. Count active entries within the window
            Long count = redisTemplate.opsForZSet().zCard(key);

            if (count != null && count >= MAX_VELOCITY_LIMIT) {
                log.warn("Velocity limit triggered for account {}. Count in window: {}", account.getAccountNumber(), count);
                throw new FraudException("Velocity limit exceeded: Maximum " + MAX_VELOCITY_LIMIT + 
                    " transactions allowed per " + VELOCITY_WINDOW_SECONDS + " seconds.");
            }

            // 3. Add the current timestamp as score and value
            redisTemplate.opsForZSet().add(key, String.valueOf(now), now);

            // 4. Set TTL on the key to prevent memory leaks if idle
            redisTemplate.expireAt(key, Instant.ofEpochMilli(now + (VELOCITY_WINDOW_SECONDS * 1000)));

        } catch (FraudException e) {
            throw e;
        } catch (Exception e) {
            // If Redis is down, log warning but do not block payments (fail-open for UX, or fail-closed for security.
            // In high-security systems we fail-closed, but here we log and let it pass or fail-open depending on setup. Let's fail-open with alert.
            log.error("Redis connection failure in velocity limit check. Defaulting to fail-open.", e);
        }
    }
}
