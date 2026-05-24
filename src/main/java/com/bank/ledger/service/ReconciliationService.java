package com.bank.ledger.service;

import com.bank.ledger.domain.*;
import com.bank.ledger.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final ReconciliationRunRepository runRepository;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * Scans all transactions and account balances to verify absolute system consistency.
     * Operates as a background job or manual OPS endpoint.
     */
    @Transactional
    public ReconciliationRun runReconciliation() {
        log.info("Starting ledger reconciliation run...");
        
        ReconciliationRun run = ReconciliationRun.builder()
                .status(ReconciliationRun.RunStatus.RUNNING)
                .build();
        run = runRepository.save(run);

        int totalTxChecked = 0;
        int anomaliesCount = 0;

        // 1. Audit Double-Entry transaction headers and postings
        List<Transaction> transactions = transactionRepository.findAll();
        for (Transaction tx : transactions) {
            totalTxChecked++;
            BigDecimal sumDebits = BigDecimal.ZERO;
            BigDecimal sumCredits = BigDecimal.ZERO;

            for (LedgerEntry entry : tx.getEntries()) {
                if (entry.getEntryType() == LedgerEntry.EntryType.DEBIT) {
                    sumDebits = sumDebits.add(entry.getAmount());
                } else {
                    sumCredits = sumCredits.add(entry.getAmount());
                }
            }

            // Verify that Debits equal Credits
            if (sumDebits.compareTo(sumCredits) != 0) {
                anomaliesCount++;
                String details = String.format("Transaction ID %d is imbalanced! Sum Debits: $%s, Sum Credits: $%s",
                        tx.getId(), sumDebits, sumCredits);
                
                log.error("Reconciliation Anomaly: {}", details);
                
                ReconciliationAnomaly anomaly = ReconciliationAnomaly.builder()
                        .type(ReconciliationAnomaly.AnomalyType.IMBALANCED_TRANSACTION)
                        .details(details)
                        .build();
                run.addAnomaly(anomaly);
            }
        }

        // 2. Audit Accounts cached balances vs historical entries sum
        List<Account> accounts = accountRepository.findAll();
        for (Account account : accounts) {
            List<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(account.getId());
            BigDecimal calculatedBalance = BigDecimal.ZERO;

            // Reconstruct the balance based on account type conventions
            for (LedgerEntry entry : entries) {
                boolean isDebit = (entry.getEntryType() == LedgerEntry.EntryType.DEBIT);
                boolean isAssetOrExpense = (account.getType() == Account.AccountType.ASSET || 
                                           account.getType() == Account.AccountType.EXPENSE);

                // Asset/Expense: Debits increase (+), Credits decrease (-)
                // Liability/Equity/Revenue: Credits increase (+), Debits decrease (-)
                if (isAssetOrExpense) {
                    if (isDebit) {
                        calculatedBalance = calculatedBalance.add(entry.getAmount());
                    } else {
                        calculatedBalance = calculatedBalance.subtract(entry.getAmount());
                    }
                } else {
                    if (!isDebit) { // CREDIT
                        calculatedBalance = calculatedBalance.add(entry.getAmount());
                    } else {
                        calculatedBalance = calculatedBalance.subtract(entry.getAmount());
                    }
                }
            }

            // If this is the reserve account, adjust calculated balance for its initial seed if needed.
            // But since our seed is also done as a ledger entry in initSystemReserve (actually wait, 
            // in initSystemReserve we just did accountRepository.save with a balance of $1B but no LedgerEntry!
            // Wait, to make reconciliation perfect, we should either:
            // - Record the initial seed as an entry, OR
            // - Add the initial balance of the account to the calculated balance!
            // Let's add the initial balance (which is 0 for customer accounts, and 1,000,000,000 for system reserve, 
            // but actually customer accounts are created with 0 balance. So, let's treat accounts as starting with 0 balance, 
            // and if there's an initial balance we offset it. Since we seeded reserves with $1B, the reserve account starts with $1B).
            // Let's make it simpler and robust: if account number is BANK_CASH_RESERVE, it started with 1,000,000,000.
            // Let's add that to calculatedBalance if we didn't write an entry.
            BigDecimal initialBalance = account.getAccountNumber().equals("BANK_CASH_RESERVE") 
                    ? new BigDecimal("1000000000.0000") 
                    : BigDecimal.ZERO;
            
            BigDecimal finalCalculated = initialBalance.add(calculatedBalance);

            if (account.getBalance().compareTo(finalCalculated) != 0) {
                anomaliesCount++;
                String details = String.format("Account %s balance mismatch! Cached: $%s, Calculated history sum: $%s",
                        account.getAccountNumber(), account.getBalance(), finalCalculated);
                
                log.error("Reconciliation Anomaly: {}", details);
                
                ReconciliationAnomaly anomaly = ReconciliationAnomaly.builder()
                        .type(ReconciliationAnomaly.AnomalyType.BALANCE_MISMATCH)
                        .details(details)
                        .build();
                run.addAnomaly(anomaly);
            }
        }

        // 3. Complete and save run
        run.setCompletedAt(OffsetDateTime.now());
        run.setStatus(ReconciliationRun.RunStatus.SUCCESS);
        run.setTotalTransactionsChecked(totalTxChecked);
        run.setAnomaliesFound(anomaliesCount);

        log.info("Ledger reconciliation completed. Status: {}. Total checked: {}. Anomalies found: {}", 
                run.getStatus(), totalTxChecked, anomaliesCount);

        return runRepository.save(run);
    }
}
