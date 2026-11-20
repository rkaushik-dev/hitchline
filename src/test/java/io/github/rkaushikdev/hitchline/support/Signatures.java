package io.github.rkaushikdev.hitchline.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads parameter types by reflection. A rule such as "the day must be a whole number" is held
 * by the compiler once a parameter is an {@code int}; the tests for those rules assert the
 * parameter types, so a change to a fractional type would fail them.
 */
public final class Signatures {

    private Signatures() {}

    /** The parameter types of every public method or constructor of {@code type} called {@code name}. */
    public static List<List<Class<?>>> parameterTypes(Class<?> type, String name) {
        Stream<Executable> methods = Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals(name))
                .map(Method.class::cast);
        Stream<Executable> constructors = name.equals("<init>")
                ? Arrays.stream(type.getConstructors()).map(Constructor.class::cast)
                : Stream.empty();
        return Stream.concat(methods, constructors)
                .map(executable -> List.of(executable.getParameterTypes()))
                .toList();
    }
}
