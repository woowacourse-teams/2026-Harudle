package com.harudle.admin.presentation;

import jakarta.validation.constraints.Min;

record ChangeDailyGenerationLimitRequest(@Min(0) int limitCount) {}
