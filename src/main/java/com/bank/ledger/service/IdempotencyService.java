package com.bank.ledger.service;

import com.bank.ledger.domain.IdempotencyRecord;
import com.bank.ledger.domain.IdempotencyRecord.IdempotencyStatus;
import com.bank.ledger.exception.BankingException;
import com.bank.ledger.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final StringRedisTemplate redisTemplate;

    private static final String REDIS_PREFIX = "idempotency:";
    private static final Duration CACHE_TTL = Duration.ofDays(1); // Keep keys cached for 1 day

    /**
     * Attempts to start processing a request under a given idempotency key.
     * Runs in a REQUIRES_NEW transaction so that the lock state is persisted even if the business transaction rolls back.
     * Returns true if this is a brand new key and execution should proceed.
     * Throws 409 Conflict if a request is already actively processing.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean startProcessing(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }

        // 1. Check Redis cache first (Fast path)
        String redisKey = REDIS_PREFIX + key;
        String cachedStatus = redisTemplate.opsForValue().get(redisKey);
        
        if (cachedStatus != null) {
            if (IdempotencyStatus.PROCESSING.name().equals(cachedStatus)) {
                throw new BankingException("Duplicate request. An identical transaction is already in progress.", HttpStatus.CONFLICT);
            }
            return false; // Key exists and is completed (handled in the controller/filter layer)
        }

        // 2. Check Database (Slow path / fallback & consistency safeguard)
        Optional<IdempotencyRecord> dbRecordOpt = repository.findById(key);
        if (dbRecordOpt.isPresent()) {
            IdempotencyRecord record = dbRecordOpt.get();
            if (record.getStatus() == IdempotencyStatus.PROCESSING) {
                // Sync Redis cache in case it evaporated
                redisTemplate.opsForValue().set(redisKey, IdempotencyStatus.PROCESSING.name(), CACHE_TTL);
                throw new BankingException("Duplicate request. An identical transaction is already in progress.", HttpStatus.CONFLICT);
            }
            // Sync completed status to Redis
            redisTemplate.opsForValue().set(redisKey, IdempotencyStatus.COMPLETED.name(), CACHE_TTL);
            return false; // Completed
        }

        // 3. Register new request key
        log.info("Registering new idempotency key: {}", key);
        try {
            // Write to database
            IdempotencyRecord newRecord = IdempotencyRecord.builder()
                .idempotencyKey(key)
                .status(IdempotencyStatus.PROCESSING)
                .build();
            repository.saveAndFlush(newRecord);

            // Cache in Redis as processing
            redisTemplate.opsForValue().set(redisKey, IdempotencyStatus.PROCESSING.name(), CACHE_TTL);
            return true;
        } catch (Exception e) {
            // Under high concurrency, constraint violations could occur if DB write raced with another thread.
            log.warn("Race condition detected on DB write for idempotency key: {}", key, e);
            throw new BankingException("Duplicate request. An identical transaction is already in progress.", HttpStatus.CONFLICT);
        }
    }

    /**
     * Saves the final response payload and completes the lifecycle of the idempotency key.
     * Runs in a REQUIRES_NEW transaction to ensure the result is saved even if the overall request had sub-transaction issues.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeResponse(String key, int responseCode, String responseBody) {
        if (key == null || key.isBlank()) {
            return;
        }

        log.info("Completing idempotency key: {} with status code {}", key, responseCode);
        
        Optional<IdempotencyRecord> recordOpt = repository.findById(key);
        if (recordOpt.isPresent()) {
            IdempotencyRecord record = recordOpt.get();
            record.setStatus(IdempotencyStatus.COMPLETED);
            record.setResponseCode(responseCode);
            record.setResponseBody(responseBody);
            repository.saveAndFlush(record);
        } else {
            // In case it wasn't registered somehow, build and save it anyway
            IdempotencyRecord record = IdempotencyRecord.builder()
                .idempotencyKey(key)
                .status(IdempotencyStatus.COMPLETED)
                .responseCode(responseCode)
                .responseBody(responseBody)
                .build();
            repository.saveAndFlush(record);
        }

        // Update Redis cache status
        String redisKey = REDIS_PREFIX + key;
        redisTemplate.opsForValue().set(redisKey, IdempotencyStatus.COMPLETED.name(), CACHE_TTL);
    }

    /**
     * Fetches the completed response for an idempotency key.
     */
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> getRecord(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(key);
    }
}
