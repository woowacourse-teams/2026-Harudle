package com.harudle.admin.presentation;

import jakarta.validation.constraints.Min;

record RestoreGenerationUsageRequest(@Min(1) int count) {}
