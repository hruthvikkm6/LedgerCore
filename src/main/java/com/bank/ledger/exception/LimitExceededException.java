package com.bank.ledger.exception;

import org.springframework.http.HttpStatus;

public class LimitExceededException extends BankingException {
    public LimitExceededException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}
