package com.bank.ledger.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "reconciliation_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationRun {

    public enum RunStatus {
        RUNNING,
        SUCCESS,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", updatable = false)
    @Builder.Default
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RunStatus status;

    @Column(name = "total_transactions_checked")
    @Builder.Default
    private Integer totalTransactionsChecked = 0;

    @Column(name = "anomalies_found")
    @Builder.Default
    private Integer anomaliesFound = 0;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ReconciliationAnomaly> anomalies = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        startedAt = OffsetDateTime.now();
    }

    public void addAnomaly(ReconciliationAnomaly anomaly) {
        anomalies.add(anomaly);
        anomaly.setRun(this);
        anomaliesFound = anomalies.size();
    }
}
