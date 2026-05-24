package com.bank.ledger.service;

import com.bank.ledger.event.TransactionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionCommitListener {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    
    private static final String KAFKA_TOPIC = "transaction-events";

    /**
     * Listens to TransactionEvents published by the LedgerEngine.
     * Fires strictly AFTER the database transaction commits, ensuring lock release is never blocked by Kafka latency.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTransactionCommitted(TransactionEvent event) {
        log.info("Database transaction committed. Publishing event to Kafka: {}", event.getTransactionId());
        try {
            kafkaTemplate.send(KAFKA_TOPIC, String.valueOf(event.getTransactionId()), event);
            log.debug("Dispatched Kafka message for committed transaction: {}", event.getTransactionId());
        } catch (Exception e) {
            log.error("Failed to publish transaction event to Kafka for transaction ID: {}. Transaction committed successfully in database.", 
                event.getTransactionId(), e);
        }
    }
}
