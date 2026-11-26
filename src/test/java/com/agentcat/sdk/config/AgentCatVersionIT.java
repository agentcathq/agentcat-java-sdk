package com.agentcat.sdk.config;

import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Runs after packaging so a release cannot ship a stale version constant. */
class AgentCatVersionIT {
    @Test
    void packagedSdkReportsItsOwnMavenVersion() throws Exception {
        Path sdkJar = Path.of(System.getProperty("sdkJar"));
        Properties maven = new Properties();
        try (JarFile jar = new JarFile(sdkJar.toFile())) {
            try (var stream = jar.getInputStream(jar.getJarEntry(
                    "META-INF/maven/com.agentcat/agentcat-java-sdk/pom.properties"))) {
                maven.load(stream);
            }
        }
        // No target/classes: resolve the SDK class from the packaged JAR.
        try (var loader = new URLClassLoader(new java.net.URL[]{sdkJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> constants = loader.loadClass("com.agentcat.sdk.config.AgentCatConstants");
            Object version = constants.getField("AGENTCAT_SDK_VERSION").get(null);
            assertEquals(maven.getProperty("version"), version);
        }
    }
}
