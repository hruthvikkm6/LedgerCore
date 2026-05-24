package com.bank.ledger.exception;

import org.springframework.http.HttpStatus;

public class FraudException extends BankingException {
    public FraudException(String message) {
        super(message, HttpStatus.FORBIDDEN);
    }
}
