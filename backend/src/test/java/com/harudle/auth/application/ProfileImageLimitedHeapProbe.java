package com.harudle.auth.application;

import com.harudle.auth.infrastructure.CwebpProfileImageEncoder;
import java.nio.file.Files;
import java.nio.file.Path;

public class ProfileImageLimitedHeapProbe {

    public static void main(String[] args) throws Exception {
        var processor = new ProfileImageProcessor(new CwebpProfileImageEncoder());
        Files.write(Path.of(args[1]), processor.process(Files.readAllBytes(Path.of(args[0]))));
    }
}
