package com.harudle.notification.repository;

import java.util.UUID;

public record NotificationReadTarget(UUID id, UUID recipientId) {}
