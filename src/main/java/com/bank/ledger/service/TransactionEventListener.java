package com.bank.ledger.service;

import com.bank.ledger.domain.AuditLog;
import com.bank.ledger.event.TransactionEvent;
import com.bank.ledger.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionEventListener {

    private final AuditLogRepository auditLogRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String DLT_TOPIC = "transaction-events-dlt";

    @KafkaListener(topics = "transaction-events", groupId = "banking-ledger-group")
    public void handleTransactionEvent(TransactionEvent event) {
        log.info("Asynchronously consuming Kafka transaction event: {}", event);

        try {
            // 1. Write an immutable audit log record to PostgreSQL
            String details = String.format("Successfully posted double-entry transaction. Type: %s, Amount: $%s, From: %s, To: %s, Description: %s",
                    event.getType(), event.getAmount(), event.getFromAccountNumber(), event.getToAccountNumber(), event.getDescription());

            AuditLog logRecord = AuditLog.builder()
                    .action("TRANSACTION_POSTED_" + event.getType())
                    .details(details)
                    .performedBy("SYSTEM_KAFKA_DAEMON")
                    .ipAddress("127.0.0.1")
                    .build();

            auditLogRepository.save(logRecord);
            log.info("Persisted immutable audit record for transaction ID: {}", event.getTransactionId());

            // 2. Trigger asynchronous customer notification (SMS/Email mock)
            sendMockNotification(event);

        } catch (Exception e) {
            log.error("Fatal error during async processing of transaction ID: {}. Routing to Dead Letter Topic (DLT).", event.getTransactionId(), e);
            sendToDlt(event);
        }
    }

    private void sendMockNotification(TransactionEvent event) {
        log.info("======================= ASYNC NOTIFICATION ENGINE =======================");
        log.info("ALERT: A successful {} of ${} has been posted.", event.getType(), event.getAmount());
        log.info("Account Pathway: {} ===> {}", event.getFromAccountNumber(), event.getToAccountNumber());
        log.info("Transaction Description: \"{}\"", event.getDescription());
        log.info("Notification dispatched via [SMS/EMAIL MOCK CLIENT] at current runtime.");
        log.info("=========================================================================");
    }

    private void sendToDlt(TransactionEvent event) {
        try {
            kafkaTemplate.send(DLT_TOPIC, String.valueOf(event.getTransactionId()), event);
            log.warn("Dispatched transaction event ID {} to Dead Letter Topic (DLT) successfully.", event.getTransactionId());
        } catch (Exception e) {
            log.error("CRITICAL: Failed to publish event {} to Dead Letter Topic (DLT)!", event.getTransactionId(), e);
        }
    }

    /**
     * DLT Consumer to capture and monitor system operational failures
     */
    @KafkaListener(topics = DLT_TOPIC, groupId = "banking-ledger-group")
    public void handleDltEvent(TransactionEvent event) {
        log.error("ALERT: Received unprocessable event in Dead Letter Topic (DLT): transactionId={}", event.getTransactionId());
    }
}
