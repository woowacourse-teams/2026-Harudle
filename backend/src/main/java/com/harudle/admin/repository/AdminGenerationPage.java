package com.harudle.admin.repository;

import java.util.List;

public record AdminGenerationPage(List<AdminGenerationSnapshot> content, long totalElements) {}
