package com.agentcat.sdk.engine;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReflectionUtils}.
 *
 * <p>Uses simple static helper classes as reflection targets — no MCP server required.
 */
class ReflectionUtilsTest {

    // ── Reflection target classes ─────────────────────────────────────────────

    static class Box {
        private String value;
        Box(String value) { this.value = value; }
    }

    static class Counter {
        private int count = 0;
    }

    /** Base class in a 3-level hierarchy. */
    static class Animal {
        private String species = "unknown";
    }

    /** Middle level — does NOT redeclare {@code species}. */
    static class Dog extends Animal {
        private String name;
        Dog(String name) { this.name = name; }
    }

    /** Leaf level — walks two levels to find {@code species}. */
    static class Poodle extends Dog {
        private String color;
        Poodle(String name, String color) {
            super(name);
            this.color = color;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // readField()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void readFieldReturnsCorrectStringValue() throws Exception {
        Box box = new Box("hello");
        assertEquals("hello", ReflectionUtils.<String>readField(box, "value"));
    }

    @Test
    void readFieldReturnsNullWhenFieldIsNull() throws Exception {
        Box box = new Box(null);
        assertNull(ReflectionUtils.readField(box, "value"));
    }

    @Test
    void readFieldReadsDifferentInstanceValues() throws Exception {
        Box a = new Box("alpha");
        Box b = new Box("beta");
        assertEquals("alpha", ReflectionUtils.<String>readField(a, "value"));
        assertEquals("beta",  ReflectionUtils.<String>readField(b, "value"));
    }

    @Test
    void readFieldThrowsForNonExistentField() {
        assertThrows(NoSuchFieldException.class,
                () -> ReflectionUtils.readField(new Box("x"), "ghost"),
                "readField must throw NoSuchFieldException for unknown fields");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // writeField()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void writeFieldUpdatesValue() throws Exception {
        Box box = new Box("before");
        ReflectionUtils.writeField(box, "value", "after");
        assertEquals("after", ReflectionUtils.<String>readField(box, "value"));
    }

    @Test
    void writeFieldSetsValueToNull() throws Exception {
        Box box = new Box("something");
        ReflectionUtils.writeField(box, "value", null);
        assertNull(ReflectionUtils.<String>readField(box, "value"));
    }

    @Test
    void writeFieldIsVisibleViaReadField() throws Exception {
        Box box = new Box("v1");
        ReflectionUtils.writeField(box, "value", "v2");
        assertEquals("v2", ReflectionUtils.<String>readField(box, "value"));
    }

    @Test
    void writeFieldDoesNotAffectOtherInstances() throws Exception {
        Box a = new Box("unchanged");
        Box b = new Box("original");
        ReflectionUtils.writeField(b, "value", "mutated");
        assertEquals("unchanged", ReflectionUtils.<String>readField(a, "value"),
                "writing to b must not affect a");
    }

    @Test
    void writeFieldThrowsForNonExistentField() {
        assertThrows(NoSuchFieldException.class,
                () -> ReflectionUtils.writeField(new Box("x"), "ghost", "val"),
                "writeField must throw NoSuchFieldException for unknown fields");
    }

    @Test
    void writeFieldCanBeCalledMultipleTimes() throws Exception {
        Box box = new Box("v1");
        ReflectionUtils.writeField(box, "value", "v2");
        ReflectionUtils.writeField(box, "value", "v3");
        assertEquals("v3", ReflectionUtils.<String>readField(box, "value"),
                "successive writes must reflect the last written value");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // findFieldInHierarchy()
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void findsFieldDeclaredDirectlyOnClass() {
        Field f = ReflectionUtils.findFieldInHierarchy(Box.class, "value");
        assertNotNull(f, "must find 'value' directly on Box");
        assertEquals(Box.class, f.getDeclaringClass());
    }

    @Test
    void findsFieldOneClassUp() {
        // Dog does not declare 'species'; it is on Animal
        Field f = ReflectionUtils.findFieldInHierarchy(Dog.class, "species");
        assertNotNull(f, "must find 'species' on parent class Animal");
        assertEquals(Animal.class, f.getDeclaringClass());
    }

    @Test
    void findsFieldTwoLevelsUp() {
        // Poodle → Dog → Animal.species
        Field f = ReflectionUtils.findFieldInHierarchy(Poodle.class, "species");
        assertNotNull(f, "must traverse two levels to find 'species'");
        assertEquals(Animal.class, f.getDeclaringClass());
    }

    @Test
    void findsFieldOnLeafClass() {
        Field f = ReflectionUtils.findFieldInHierarchy(Poodle.class, "color");
        assertNotNull(f);
        assertEquals(Poodle.class, f.getDeclaringClass());
    }

    @Test
    void returnsNullWhenFieldNotFoundAnywhere() {
        Field f = ReflectionUtils.findFieldInHierarchy(Poodle.class, "doesNotExist");
        assertNull(f, "must return null when field is absent from the entire hierarchy");
    }

    @Test
    void returnsNullWhenStartingClassIsNull() {
        // Passing null must not throw — it should return null immediately
        Field f = ReflectionUtils.findFieldInHierarchy(null, "value");
        assertNull(f);
    }

    @Test
    void findsFieldNameCorrectly() {
        Field f = ReflectionUtils.findFieldInHierarchy(Dog.class, "name");
        assertNotNull(f);
        assertEquals("name", f.getName());
    }

    @Test
    void foundFieldIsAccessible() throws Exception {
        // After finding the field, we must be able to make it accessible and read it
        Poodle poodle = new Poodle("Rex", "apricot");
        Field colorField = ReflectionUtils.findFieldInHierarchy(Poodle.class, "color");
        assertNotNull(colorField);
        colorField.setAccessible(true);
        assertEquals("apricot", colorField.get(poodle));
    }
}
