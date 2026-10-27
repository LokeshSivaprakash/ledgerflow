package dev.ledgerflow.account;

import java.util.UUID;

public record Account(UUID id, String ownerSubject, String currency, long balanceMinor, boolean allowNegative) {}
