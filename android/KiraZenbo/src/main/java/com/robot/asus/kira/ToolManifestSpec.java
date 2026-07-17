package com.robot.asus.kira;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure-Java source of truth for the six fixed device tool contracts. */
final class ToolManifestSpec {
    static final class Property {
        final String name;
        final String type;
        final Number minimum;
        final Number maximum;
        final List<String> allowedValues;

        Property(String name, String type, Number minimum, Number maximum, String... allowedValues) {
            this.name = name;
            this.type = type;
            this.minimum = minimum;
            this.maximum = maximum;
            this.allowedValues = Collections.unmodifiableList(Arrays.asList(allowedValues));
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
                List<Property> inputProperties,
                List<String> requiredInputs,
                List<Property> resultProperties,
                List<String> requiredResults
        ) {
            this.name = name;
            this.owner = owner;
            this.sideEffect = sideEffect;
            this.idempotent = idempotent;
            this.timeoutMs = 5_000;
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
            definition("start_robot_following", "native", "physical", false,
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
                    properties(OK, property("emotion", "string"), property("durationMs", "integer")),
                    names("ok", "emotion", "durationMs")),
            definition("go_to_sleep", "web", "ui", true,
                    properties(), names(),
                    properties(OK, property("sleeping", "boolean")), names("ok", "sleeping"))
    ));

    private ToolManifestSpec() { }

    static List<Definition> definitions() {
        return DEFINITIONS;
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
        return new Definition(name, owner, sideEffect, idempotent,
                inputs, requiredInputs, results, requiredResults);
    }

    private static Property property(String name, String type) {
        return property(name, type, null, null);
    }

    private static Property property(String name, String type, Number minimum, Number maximum, String... values) {
        return new Property(name, type, minimum, maximum, values);
    }

    private static List<Property> properties(Property... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    private static List<String> names(String... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}
