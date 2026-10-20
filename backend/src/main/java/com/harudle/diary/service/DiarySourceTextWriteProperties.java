package com.harudle.diary.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("harudle.diary.source-text-encryption")
public record DiarySourceTextWriteProperties(@DefaultValue("true") boolean newWritesEnabled) {
}
