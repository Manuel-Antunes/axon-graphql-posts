package dev.manuelantunes.axonposts.infrastructure.axon;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.axonframework.common.ReflectionUtils;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.annotation.reflection.InjectEntityId;

final class EntityIdType {
    private EntityIdType() {
    }

    static Optional<Class<?>> of(Class<?> entity) {
        EventSourcedEntity declaration = entity.getAnnotation(EventSourcedEntity.class);
        if (declaration == null) {
            return Optional.empty();
        }
        return injectedIntoACreator(entity, declaration)
                .or(() -> carriedByATaggedEvent(entity, declaration));
    }

    private static Optional<Class<?>> injectedIntoACreator(Class<?> entity, EventSourcedEntity declaration) {
        Set<Class<?>> found = new LinkedHashSet<>();
        for (Class<?> declaring : entityAndItsConcreteTypes(entity, declaration)) {
            for (Executable creator : creatorsOf(declaring)) {
                for (Parameter parameter : creator.getParameters()) {
                    if (parameter.isAnnotationPresent(InjectEntityId.class)) {
                        found.add(parameter.getType());
                    }
                }
            }
        }
        return onlyOne(found, entity, "@InjectEntityId");
    }

    private static Optional<Class<?>> carriedByATaggedEvent(Class<?> entity, EventSourcedEntity declaration) {
        String tagKey = tagKeyOf(entity, declaration);
        Set<Class<?>> found = new LinkedHashSet<>();
        for (Class<?> event : eventsSourcedBy(entity, declaration)) {
            found.addAll(typesTaggedAs(event, tagKey));
        }
        return onlyOne(found, entity, "@EventTag(\"" + tagKey + "\")");
    }

    private static String tagKeyOf(Class<?> entity, EventSourcedEntity declaration) {
        return declaration.tagKey().isEmpty() ? entity.getSimpleName() : declaration.tagKey();
    }

    private static Set<Class<?>> entityAndItsConcreteTypes(Class<?> entity, EventSourcedEntity declaration) {
        Set<Class<?>> types = new LinkedHashSet<>();
        types.add(entity);
        types.addAll(List.of(declaration.concreteTypes()));
        return types;
    }

    private static Set<Class<?>> eventsSourcedBy(Class<?> entity, EventSourcedEntity declaration) {
        Set<Class<?>> events = new LinkedHashSet<>();
        for (Class<?> declaring : entityAndItsConcreteTypes(entity, declaration)) {
            for (Executable creator : creatorsOf(declaring)) {
                events.addAll(List.of(creator.getParameterTypes()));
            }
            for (Method method : ReflectionUtils.methodsOf(declaring)) {
                if (method.isAnnotationPresent(EventSourcingHandler.class)) {
                    events.addAll(List.of(method.getParameterTypes()));
                }
            }
        }
        return events;
    }

    private static List<Executable> creatorsOf(Class<?> type) {
        List<Executable> creators = new ArrayList<>();
        for (Executable constructor : type.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(EntityCreator.class)) {
                creators.add(constructor);
            }
        }
        for (Method method : ReflectionUtils.methodsOf(type)) {
            if (method.isAnnotationPresent(EntityCreator.class)) {
                creators.add(method);
            }
        }
        return creators;
    }

    private static Set<Class<?>> typesTaggedAs(Class<?> event, String tagKey) {
        Set<Class<?>> tagged = new LinkedHashSet<>();
        for (Field field : ReflectionUtils.fieldsOf(event)) {
            if (anyTagMatches(field.getAnnotationsByType(EventTag.class), tagKey, field.getName())) {
                tagged.add(field.getType());
            }
        }
        for (Method method : ReflectionUtils.methodsOf(event)) {
            String property = accessedPropertyOf(method.getName());
            if (anyTagMatches(method.getAnnotationsByType(EventTag.class), tagKey, property)) {
                tagged.add(method.getReturnType());
            }
        }
        return tagged;
    }

    private static boolean anyTagMatches(EventTag[] tags, String tagKey, String memberName) {
        for (EventTag tag : tags) {
            if (tagKey.equals(tag.key().isEmpty() ? memberName : tag.key())) {
                return true;
            }
        }
        return false;
    }

    private static String accessedPropertyOf(String methodName) {
        boolean getterByConvention = methodName.startsWith("get")
                && methodName.length() >= 4
                && Character.isUpperCase(methodName.charAt(3));
        return getterByConvention
                ? methodName.substring(3, 4).toLowerCase(Locale.ROOT) + methodName.substring(4)
                : methodName;
    }

    private static Optional<Class<?>> onlyOne(Set<Class<?>> found, Class<?> entity, String declaredBy) {
        if (found.size() > 1) {
            throw new IllegalStateException(
                    entity.getName() + " has more than one candidate id type declared by " + declaredBy
                            + ": " + found + ". Axon registers one repository per id type, so the entity "
                            + "cannot be loaded under two. Make the declarations agree, or state the type "
                            + "with @IdType on the entity.");
        }
        return found.stream().findFirst();
    }
}
