package com.bank.ledger.service;

import com.bank.ledger.domain.Account;
import com.bank.ledger.domain.Role;
import com.bank.ledger.domain.User;
import com.bank.ledger.exception.BankingException;
import com.bank.ledger.exception.InsufficientFundsException;
import com.bank.ledger.repository.AccountRepository;
import com.bank.ledger.repository.RoleRepository;
import com.bank.ledger.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:bankingtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "spring.kafka.listener.auto-startup=false",
    "spring.data.redis.repositories.enabled=false",
    "spring.kafka.producer.properties.max.block.ms=250"
})
public class LedgerEngineConcurrencyTest {

    @Autowired
    private LedgerEngine ledgerEngine;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private String testAccountNumber;

    @BeforeEach
    public void setUp() {
        // Ensure system reserve is initialized
        ledgerEngine.initSystemReserve();

        // Register dummy customer user
        User user = User.builder()
                .username("concurrency_user_" + UUID.randomUUID().toString().substring(0, 5))
                .email("concurrency_" + UUID.randomUUID().toString().substring(0, 5) + "@bank.com")
                .password("hashed_password")
                .build();
        user = userRepository.save(user);

        // Create starting customer checking account with exactly $150
        testAccountNumber = "ACT" + (100000000000L + (long) (Math.random() * 900000000000L));
        Account account = Account.builder()
                .user(user)
                .accountNumber(testAccountNumber)
                .type(Account.AccountType.LIABILITY)
                .currency("USD")
                .balance(new BigDecimal("150.0000"))
                .status(Account.AccountStatus.ACTIVE)
                .build();
        accountRepository.save(account);
    }

    @Test
    public void testConcurrentWithdrawalsPreventDoubleSpend() throws InterruptedException {
        int numberOfThreads = 5;
        BigDecimal withdrawalAmount = new BigDecimal("50.0000"); // $50 each

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successfulTransactions = new AtomicInteger(0);
        AtomicInteger failedTransactions = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                try {
                    latch.await(); // Hold all threads at start gate
                    
                    // Attempt withdrawal using a unique idempotency key per thread
                    String idempotencyKey = UUID.randomUUID().toString();
                    ledgerEngine.withdraw(testAccountNumber, withdrawalAmount, "Concurrent Withdrawal Test", idempotencyKey);
                    
                    successfulTransactions.incrementAndGet();
                } catch (BankingException e) {
                    failedTransactions.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Unexpected exception in thread: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release the gates! All 5 threads hit the LedgerEngine concurrently.
        latch.countDown();
        doneLatch.await(); // Wait for all threads to finish

        // Assertions
        // Starting balance is $150. We attempt 5 withdrawals of $50 each.
        // Pessimistic locking must ensure that EXACTLY 3 withdrawals succeed and 2 fail.
        assertThat(successfulTransactions.get()).isEqualTo(3);
        assertThat(failedTransactions.get()).isEqualTo(2);

        // Reload the account balance from DB to verify it's exactly $0.0000
        Account updatedAccount = accountRepository.findByAccountNumber(testAccountNumber).orElseThrow();
        assertThat(updatedAccount.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        executor.shutdown();
    }
}
