package com.robot.asus.kira;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure-Java source of truth for the eight allowlisted device tool contracts. */
final class ToolManifestSpec {
    static final class Property {
        final String name;
        final String type;
        final Number minimum;
        final Number maximum;
        final List<String> allowedValues;
        final Integer minLength;
        final Integer maxLength;
        final String pattern;
        final String format;
        final Boolean constant;

        Property(String name, String type, Number minimum, Number maximum, String... allowedValues) {
            this(name, type, minimum, maximum, null, null, null, null, null, allowedValues);
        }

        Property(String name, String type, Number minimum, Number maximum,
                 Integer minLength, Integer maxLength, String pattern, String format,
                 Boolean constant, String... allowedValues) {
            this.name = name;
            this.type = type;
            this.minimum = minimum;
            this.maximum = maximum;
            this.allowedValues = Collections.unmodifiableList(Arrays.asList(allowedValues));
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.pattern = pattern;
            this.format = format;
            this.constant = constant;
        }
    }

    static final class Definition {
        final String name;
        final String owner;
        final String sideEffect;
        final boolean idempotent;
        final int timeoutMs;
        final List<Property> inputProperties;
        final List<String> requiredInputs;
        final List<Property> resultProperties;
        final List<String> requiredResults;

        Definition(
                String name,
                String owner,
                String sideEffect,
                boolean idempotent,
                int timeoutMs,
                List<Property> inputProperties,
                List<String> requiredInputs,
                List<Property> resultProperties,
                List<String> requiredResults
        ) {
            this.name = name;
            this.owner = owner;
            this.sideEffect = sideEffect;
            this.idempotent = idempotent;
            this.timeoutMs = timeoutMs;
            this.inputProperties = inputProperties;
            this.requiredInputs = requiredInputs;
            this.resultProperties = resultProperties;
            this.requiredResults = requiredResults;
        }
    }

    private static final Property ACCEPTED = property("accepted", "boolean");
    private static final Property OK = property("ok", "boolean");

    private static final List<Definition> DEFINITIONS = Collections.unmodifiableList(Arrays.asList(
            definition("get_system_status", "native", "none", true,
                    properties(), names(),
                    properties(ACCEPTED, property("robotReady", "boolean"), property("moving", "boolean"),
                            property("androidSdk", "integer"), property("robotModel", "string")),
                    names("accepted", "robotReady", "moving", "androidSdk", "robotModel")),
            // Attention stop (2 s) + avoidance (1.5 s) + follow (3 s) + transport margin (1 s).
            definition("start_robot_following", "native", "physical", false, 7_500,
                    properties(property("enablePreview", "boolean"), property("largePreview", "boolean")), names(),
                    properties(ACCEPTED), names("accepted")),
            definition("stop_robot_following", "native", "physical", true,
                    properties(), names(), properties(ACCEPTED), names("accepted")),
            definition("look_at_user", "native", "physical", true,
                    properties(property("doa", "number", -180, 180)), names("doa"),
                    properties(ACCEPTED), names("accepted")),
            definition("show_emotion", "web", "ui", true,
                    properties(
                            property("emotion", "string", null, null,
                                    "NEUTRAL", "HAPPY", "CURIOUS", "CONCERNED", "EXCITED"),
                            property("durationMs", "integer", 0, 30_000)),
                    names("emotion"),
                    properties(
                            OK,
                            property("emotion", "string", null, null,
                                    "NEUTRAL", "HAPPY", "CURIOUS", "CONCERNED", "EXCITED"),
                            property("durationMs", "integer", 0, 30_000)),
                    names("ok", "emotion", "durationMs")),
            definition("go_to_sleep", "web", "ui", true,
                    properties(), names(),
                    properties(OK, property("sleeping", "boolean")), names("ok", "sleeping")),
            // Attention stop (2 s) + avoidance (1.5 s) + move (2 s) + transport margin (1 s).
            definition("move_robot", "native", "physical", false, 6_500,
                    properties(property("direction", "string", null, null,
                            "forward", "backward", "left", "right")), names("direction"),
                    properties(ACCEPTED), names("accepted")),
            definition("capture_camera", "native", "none", false,
                    properties(), names(),
                    properties(new Property("accepted", "boolean", null, null,
                                    null, null, null, null, true),
                            stringProperty("artifactId", null, 36, null, "uuid"),
                            property("mimeType", "string", null, null, "image/jpeg"),
                            property("byteLength", "integer", 1, 524_288),
                            stringProperty("sha256", 64, 64, "^[a-f0-9]{64}$", null),
                            property("width", "integer", 1, 1280),
                            property("height", "integer", 1, 1280),
                            stringProperty("capturedAt", null, 64, null, "date-time"),
                            stringProperty("imageBase64", 4, 699_052, null, null)),
                    names("accepted", "artifactId", "mimeType", "byteLength", "sha256", "width",
                            "height", "capturedAt", "imageBase64"))
    ));

    private ToolManifestSpec() { }

    static List<Definition> definitions() {
        return DEFINITIONS;
    }

    static int timeoutMs(String name) {
        for (Definition definition : DEFINITIONS) if (definition.name.equals(name)) return definition.timeoutMs;
        throw new IllegalArgumentException("Unsupported device tool");
    }

    private static Definition definition(
            String name,
            String owner,
            String sideEffect,
            boolean idempotent,
            List<Property> inputs,
            List<String> requiredInputs,
            List<Property> results,
            List<String> requiredResults
    ) {
        return definition(name, owner, sideEffect, idempotent, 5_000,
                inputs, requiredInputs, results, requiredResults);
    }

    private static Definition definition(
            String name,
            String owner,
            String sideEffect,
            boolean idempotent,
            int timeoutMs,
            List<Property> inputs,
            List<String> requiredInputs,
            List<Property> results,
            List<String> requiredResults
    ) {
        return new Definition(name, owner, sideEffect, idempotent, timeoutMs,
                inputs, requiredInputs, results, requiredResults);
    }

    private static Property property(String name, String type) {
        return property(name, type, null, null);
    }

    private static Property property(String name, String type, Number minimum, Number maximum, String... values) {
        return new Property(name, type, minimum, maximum, values);
    }

    private static Property stringProperty(String name, Integer minLength, Integer maxLength,
                                           String pattern, String format) {
        return new Property(name, "string", null, null, minLength, maxLength, pattern, format, null);
    }

    private static List<Property> properties(Property... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    private static List<String> names(String... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}
