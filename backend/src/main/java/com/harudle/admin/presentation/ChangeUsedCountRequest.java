package com.harudle.admin.presentation;

import jakarta.validation.constraints.Min;

record ChangeUsedCountRequest(@Min(0) int usedCount) {}
