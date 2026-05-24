package com.bank.ledger.controller;

import com.bank.ledger.domain.ReconciliationAnomaly;
import com.bank.ledger.domain.ReconciliationRun;
import com.bank.ledger.service.ReconciliationService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @PostMapping("/reconcile")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
    @Transactional
    public ResponseEntity<ReconciliationReport> triggerReconciliation() {
        log.info("Operational request received: Triggering manual ledger reconciliation.");
        
        ReconciliationRun run = reconciliationService.runReconciliation();

        List<AnomalyDetail> anomalyDetails = run.getAnomalies().stream()
                .map(a -> AnomalyDetail.builder()
                        .id(a.getId())
                        .type(a.getType().name())
                        .details(a.getDetails())
                        .createdAt(a.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        ReconciliationReport report = ReconciliationReport.builder()
                .runId(run.getId())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .status(run.getStatus().name())
                .totalTransactionsChecked(run.getTotalTransactionsChecked())
                .anomaliesFound(run.getAnomaliesFound())
                .anomalies(anomalyDetails)
                .build();

        return ResponseEntity.ok(report);
    }

    @Data
    @Builder
    public static class ReconciliationReport {
        private Long runId;
        private OffsetDateTime startedAt;
        private OffsetDateTime completedAt;
        private String status;
        private Integer totalTransactionsChecked;
        private Integer anomaliesFound;
        private List<AnomalyDetail> anomalies;
    }

    @Data
    @Builder
    public static class AnomalyDetail {
        private Long id;
        private String type;
        private String details;
        private OffsetDateTime createdAt;
    }
}
