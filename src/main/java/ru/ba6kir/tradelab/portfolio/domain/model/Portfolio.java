package ru.ba6kir.tradelab.portfolio.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Portfolio(UUID id,String name,String description,String baseCurrency,String status,Instant createdAt,Instant updatedAt) {}
