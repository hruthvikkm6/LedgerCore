package com.bank.ledger.service;

import com.bank.ledger.domain.Account;
import com.bank.ledger.domain.LedgerEntry;
import com.bank.ledger.domain.Transaction;
import com.bank.ledger.domain.Transaction.TransactionStatus;
import com.bank.ledger.domain.Transaction.TransactionType;
import com.bank.ledger.event.TransactionEvent;
import com.bank.ledger.exception.AccountFrozenException;
import com.bank.ledger.exception.AccountNotFoundException;
import com.bank.ledger.exception.BankingException;
import com.bank.ledger.exception.InsufficientFundsException;
import com.bank.ledger.repository.AccountRepository;
import com.bank.ledger.repository.LedgerEntryRepository;
import com.bank.ledger.repository.TransactionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerEngine {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final FraudDetectionService fraudDetectionService;
    private final ApplicationEventPublisher eventPublisher;

    public static final String SYSTEM_RESERVE_ACCOUNT = "BANK_CASH_RESERVE";

    @PostConstruct
    @Transactional
    public void initSystemReserve() {
        if (!accountRepository.existsByAccountNumber(SYSTEM_RESERVE_ACCOUNT)) {
            Account reserve = Account.builder()
                .accountNumber(SYSTEM_RESERVE_ACCOUNT)
                .type(Account.AccountType.ASSET)
                .currency("USD")
                .balance(new BigDecimal("1000000000.0000")) // Initialize with $1 Billion system reserve
                .status(Account.AccountStatus.ACTIVE)
                .build();
            accountRepository.save(reserve);
            log.info("System Reserve Account initialized successfully with 1,000,000,000.0000 USD.");
        }
    }

    /**
     * Handles transfer of funds between two accounts.
     * Locks accounts immediately in alphabetical order of their account numbers to prevent deadlocks and bypass JPA caching traps.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transaction transfer(String fromAccountNumber, String toAccountNumber, BigDecimal amount, String description, String idempotencyKey) {
        if (fromAccountNumber.equals(toAccountNumber)) {
            throw new BankingException("Source and destination accounts must be different.", HttpStatus.BAD_REQUEST);
        }

        // 1. Lock accounts immediately in alphabetical order (Natural unique key)
        Account lockedFrom;
        Account lockedTo;
        if (fromAccountNumber.compareTo(toAccountNumber) < 0) {
            lockedFrom = accountRepository.findByAccountNumberWithLock(fromAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Source account " + fromAccountNumber + " not found."));
            lockedTo = accountRepository.findByAccountNumberWithLock(toAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Destination account " + toAccountNumber + " not found."));
        } else {
            lockedTo = accountRepository.findByAccountNumberWithLock(toAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Destination account " + toAccountNumber + " not found."));
            lockedFrom = accountRepository.findByAccountNumberWithLock(fromAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Source account " + fromAccountNumber + " not found."));
        }

        // 2. Status checks
        if (lockedFrom.getStatus() == Account.AccountStatus.FROZEN) {
            throw new AccountFrozenException("Source account " + fromAccountNumber + " is frozen.");
        }
        if (lockedTo.getStatus() == Account.AccountStatus.FROZEN) {
            throw new AccountFrozenException("Destination account " + toAccountNumber + " is frozen.");
        }

        // 3. Fraud and limit checks
        fraudDetectionService.validateTransactionLimits(lockedFrom, amount);
        fraudDetectionService.checkVelocityLimit(lockedFrom);

        // 4. Balance check
        if (lockedFrom.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient funds in account " + fromAccountNumber + ". Available balance: $" + lockedFrom.getBalance());
        }

        // 5. Post double-entry transaction
        Transaction transaction = Transaction.builder()
            .idempotencyKey(idempotencyKey)
            .type(TransactionType.TRANSFER)
            .description(description)
            .status(TransactionStatus.POSTED)
            .build();
        transaction = transactionRepository.save(transaction);

        lockedFrom.applyEntry(LedgerEntry.EntryType.DEBIT, amount);
        lockedTo.applyEntry(LedgerEntry.EntryType.CREDIT, amount);

        accountRepository.save(lockedFrom);
        accountRepository.save(lockedTo);

        LedgerEntry debitEntry = LedgerEntry.builder()
            .transaction(transaction)
            .account(lockedFrom)
            .entryType(LedgerEntry.EntryType.DEBIT)
            .amount(amount)
            .balanceAfter(lockedFrom.getBalance())
            .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
            .transaction(transaction)
            .account(lockedTo)
            .entryType(LedgerEntry.EntryType.CREDIT)
            .amount(amount)
            .balanceAfter(lockedTo.getBalance())
            .build();

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        transaction.addEntry(debitEntry);
        transaction.addEntry(creditEntry);

        // 6. Publish internal Spring event (Processed outside transactional boundary after commit)
        publishEvent(transaction, fromAccountNumber, toAccountNumber, amount, description);

        log.info("Successful Transfer transaction id={}: {} -> {} (Amount: ${})", 
            transaction.getId(), fromAccountNumber, toAccountNumber, amount);
        return transaction;
    }

    /**
     * Handles deposit into a customer account.
     * System debit on BANK_CASH_RESERVE (ASSET increases), system credit on CUSTOMER_ACCOUNT (LIABILITY increases).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transaction deposit(String accountNumber, BigDecimal amount, String description, String idempotencyKey) {
        if (accountNumber.equals(SYSTEM_RESERVE_ACCOUNT)) {
            throw new BankingException("Cannot directly deposit to system reserve.", HttpStatus.BAD_REQUEST);
        }

        // Lock in alphabetical order
        Account lockedCustomer;
        Account lockedReserve;
        if (accountNumber.compareTo(SYSTEM_RESERVE_ACCOUNT) < 0) {
            lockedCustomer = accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account " + accountNumber + " not found."));
            lockedReserve = accountRepository.findByAccountNumberWithLock(SYSTEM_RESERVE_ACCOUNT).get();
        } else {
            lockedReserve = accountRepository.findByAccountNumberWithLock(SYSTEM_RESERVE_ACCOUNT).get();
            lockedCustomer = accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account " + accountNumber + " not found."));
        }

        if (lockedCustomer.getStatus() == Account.AccountStatus.FROZEN) {
            throw new AccountFrozenException("Account " + accountNumber + " is frozen.");
        }

        Transaction transaction = Transaction.builder()
            .idempotencyKey(idempotencyKey)
            .type(TransactionType.DEPOSIT)
            .description(description)
            .status(TransactionStatus.POSTED)
            .build();
        transaction = transactionRepository.save(transaction);

        // Deposit: Debit reserve (Assets increase), Credit customer (Liabilities increase)
        lockedReserve.applyEntry(LedgerEntry.EntryType.DEBIT, amount);
        lockedCustomer.applyEntry(LedgerEntry.EntryType.CREDIT, amount);

        accountRepository.save(lockedReserve);
        accountRepository.save(lockedCustomer);

        LedgerEntry reserveDebit = LedgerEntry.builder()
            .transaction(transaction)
            .account(lockedReserve)
            .entryType(LedgerEntry.EntryType.DEBIT)
            .amount(amount)
            .balanceAfter(lockedReserve.getBalance())
            .build();

        LedgerEntry customerCredit = LedgerEntry.builder()
            .transaction(transaction)
            .account(lockedCustomer)
            .entryType(LedgerEntry.EntryType.CREDIT)
            .amount(amount)
            .balanceAfter(lockedCustomer.getBalance())
            .build();

        ledgerEntryRepository.save(reserveDebit);
        ledgerEntryRepository.save(customerCredit);

        transaction.addEntry(reserveDebit);
        transaction.addEntry(customerCredit);

        publishEvent(transaction, SYSTEM_RESERVE_ACCOUNT, accountNumber, amount, description);

        log.info("Successful Deposit transaction id={}: Deposit to {} (Amount: ${})", 
            transaction.getId(), accountNumber, amount);
        return transaction;
    }

    /**
     * Handles withdrawal from a customer account.
     * Debit CUSTOMER_ACCOUNT (LIABILITY decreases), credit BANK_CASH_RESERVE (ASSET decreases).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transaction withdraw(String accountNumber, BigDecimal amount, String description, String idempotencyKey) {
        if (accountNumber.equals(SYSTEM_RESERVE_ACCOUNT)) {
            throw new BankingException("Cannot directly withdraw from system reserve.", HttpStatus.BAD_REQUEST);
        }

        // Lock in alphabetical order immediately to fetch fresh database values
        Account lockedCustomer;
        Account lockedReserve;
        if (accountNumber.compareTo(SYSTEM_RESERVE_ACCOUNT) < 0) {
            lockedCustomer = accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account " + accountNumber + " not found."));
            lockedReserve = accountRepository.findByAccountNumberWithLock(SYSTEM_RESERVE_ACCOUNT).get();
        } else {
            lockedReserve = accountRepository.findByAccountNumberWithLock(SYSTEM_RESERVE_ACCOUNT).get();
            lockedCustomer = accountRepository.findByAccountNumberWithLock(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException("Account " + accountNumber + " not found."));
        }

        if (lockedCustomer.getStatus() == Account.AccountStatus.FROZEN) {
            throw new AccountFrozenException("Account " + accountNumber + " is frozen.");
        }

        // Limit and Fraud checks
        fraudDetectionService.validateTransactionLimits(lockedCustomer, amount);
        fraudDetectionService.checkVelocityLimit(lockedCustomer);

        if (lockedCustomer.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient funds in account " + accountNumber + ". Available balance: $" + lockedCustomer.getBalance());
        }

        Transaction transaction = Transaction.builder()
            .idempotencyKey(idempotencyKey)
            .type(TransactionType.WITHDRAWAL)
            .description(description)
            .status(TransactionStatus.POSTED)
            .build();
        transaction = transactionRepository.save(transaction);

        // Withdraw: Debit customer (Liabilities decrease), Credit reserve (Assets decrease)
        lockedCustomer.applyEntry(LedgerEntry.EntryType.DEBIT, amount);
        lockedReserve.applyEntry(LedgerEntry.EntryType.CREDIT, amount);

        accountRepository.save(lockedCustomer);
        accountRepository.save(lockedReserve);

        LedgerEntry customerDebit = LedgerEntry.builder()
            .transaction(transaction)
            .account(lockedCustomer)
            .entryType(LedgerEntry.EntryType.DEBIT)
            .amount(amount)
            .balanceAfter(lockedCustomer.getBalance())
            .build();

        LedgerEntry reserveCredit = LedgerEntry.builder()
            .transaction(transaction)
            .account(lockedReserve)
            .entryType(LedgerEntry.EntryType.CREDIT)
            .amount(amount)
            .balanceAfter(lockedReserve.getBalance())
            .build();

        ledgerEntryRepository.save(customerDebit);
        ledgerEntryRepository.save(reserveCredit);

        transaction.addEntry(customerDebit);
        transaction.addEntry(reserveCredit);

        publishEvent(transaction, accountNumber, SYSTEM_RESERVE_ACCOUNT, amount, description);

        log.info("Successful Withdrawal transaction id={}: Withdraw from {} (Amount: ${})", 
            transaction.getId(), accountNumber, amount);
        return transaction;
    }

    private void publishEvent(Transaction tx, String from, String to, BigDecimal amount, String desc) {
        try {
            TransactionEvent event = TransactionEvent.builder()
                .transactionId(tx.getId())
                .type(tx.getType().name())
                .fromAccountNumber(from)
                .toAccountNumber(to)
                .amount(amount)
                .timestamp(OffsetDateTime.now())
                .description(desc)
                .build();
            
            eventPublisher.publishEvent(event);
            log.debug("Published internal ApplicationEvent for transaction id={}", tx.getId());
        } catch (Exception e) {
            log.error("Failed to publish Spring transaction event. Event details: transactionId={}", tx.getId(), e);
        }
    }
}
