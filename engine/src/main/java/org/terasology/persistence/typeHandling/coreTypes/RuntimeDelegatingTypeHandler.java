/*
 * Copyright 2018 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.persistence.typeHandling.coreTypes;

import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.persistence.typeHandling.PersistedData;
import org.terasology.persistence.typeHandling.PersistedDataMap;
import org.terasology.persistence.typeHandling.PersistedDataSerializer;
import org.terasology.persistence.typeHandling.TypeHandler;
import org.terasology.persistence.typeHandling.TypeHandlerFactoryContext;
import org.terasology.persistence.typeHandling.TypeSerializationLibrary;
import org.terasology.reflection.TypeInfo;
import org.terasology.utilities.ReflectionUtil;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

/**
 * Delegates serialization of a value to a handler of its runtime type if needed. It is used in
 * cases where a subclass instance can be referred to as its supertype. As such, it is meant
 * for internal use in another {@link TypeHandler} only, and is never directly registered
 * in a {@link TypeSerializationLibrary}.
 *
 * @param <T> The base type whose instances may be delegated to a subtype's {@link TypeHandler} at runtime.
 */
public class RuntimeDelegatingTypeHandler<T> extends TypeHandler<T> {
    static final String TYPE_FIELD = "@type";
    static final String VALUE_FIELD = "@value";

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeDelegatingTypeHandler.class);

    // TODO: Use Optional
    private TypeHandler<T> delegateHandler;
    private TypeInfo<T> typeInfo;
    private TypeSerializationLibrary typeSerializationLibrary;
    private ClassLoader[] classLoaders;

    public RuntimeDelegatingTypeHandler(TypeHandler<T> delegateHandler, TypeInfo<T> typeInfo, TypeHandlerFactoryContext context) {
        this.delegateHandler = delegateHandler;
        this.typeInfo = typeInfo;
        this.typeSerializationLibrary = context.getTypeSerializationLibrary();
        this.classLoaders = context.getClassLoaders();
    }

    @Override
    public PersistedData serializeNonNull(T value, PersistedDataSerializer serializer) {
        // If primitive, don't go looking for the runtime type, serialize as is
        if (typeInfo.getRawType().isPrimitive()) {
            if (delegateHandler != null) {
                return delegateHandler.serialize(value, serializer);
            }

            LOGGER.error("Primitive {} does not have a TypeHandler", typeInfo.getRawType().getName());
            return serializer.serializeNull();
        }

        TypeHandler<T> chosenHandler = delegateHandler;
        Class<?> runtimeClass = getRuntimeTypeIfMoreSpecific(typeInfo, value);

        if (!typeInfo.getRawType().equals(runtimeClass)) {
            Optional<TypeHandler<?>> runtimeTypeHandler = typeSerializationLibrary.getTypeHandler((Type) runtimeClass, classLoaders);

            chosenHandler = (TypeHandler<T>) runtimeTypeHandler
                    .map(typeHandler -> {
                        if (delegateHandler == null) {
                            return typeHandler;
                        } else if (typeHandler.getClass().equals(delegateHandler.getClass())) {
                            // Both handlers are of same type, use delegateHandler
                            return delegateHandler;
                        } else if (!(typeHandler instanceof ObjectFieldMapTypeHandler)) {
                            // Custom handler for runtime type
                            return typeHandler;
                        } else if (!(delegateHandler instanceof ObjectFieldMapTypeHandler)) {
                            // Custom handler for specified type
                            return delegateHandler;
                        }

                        return typeHandler;
                    })
                    .orElse(delegateHandler);
        }

        if (chosenHandler == delegateHandler) {
            return delegateHandler.serialize(value, serializer);
        }

        Map<String, PersistedData> typeValuePersistedDataMap = Maps.newLinkedHashMap();

        typeValuePersistedDataMap.put(
                TYPE_FIELD,
                serializer.serialize(runtimeClass.getName())
        );

        typeValuePersistedDataMap.put(
                VALUE_FIELD,
                chosenHandler.serialize(value, serializer)
        );

        return serializer.serialize(typeValuePersistedDataMap);
    }

    private static <T> Class<?> getRuntimeTypeIfMoreSpecific(TypeInfo<T> typeInfo, T value) {
        if (value == null) {
            return typeInfo.getRawType();
        }

        Class<?> runtimeClass = value.getClass();

        if (typeInfo.getRawType().isInterface()) {
            // Given type is interface, use runtime type which will be a class and will have data
            return runtimeClass;
        } else if (typeInfo.getType() instanceof Class) {
            // If given type is a simple class, use more specific runtime type
            return runtimeClass;
        }

        // Given type has more information than runtime type, use that
        return typeInfo.getRawType();
    }

    @SuppressWarnings({"unchecked"})
    @Override
    public Optional<T> deserialize(PersistedData data) {
        if (!data.isValueMap()) {
            return delegateHandler.deserialize(data);
        }

        PersistedDataMap valueMap = data.getAsValueMap();

        if (!valueMap.has(TYPE_FIELD) || !valueMap.has(VALUE_FIELD)) {
            return delegateHandler.deserialize(data);
        }

        String runtimeTypeName = valueMap.getAsString(TYPE_FIELD);

        Optional<Class<?>> typeToDeserializeAs = ReflectionUtil.findClassInClassLoaders(runtimeTypeName, classLoaders);

        if (!typeToDeserializeAs.isPresent()) {
            LOGGER.error("Cannot find class to deserialize {}", runtimeTypeName);
            return Optional.empty();
        }

        if (!typeInfo.getRawType().isAssignableFrom(typeToDeserializeAs.get())) {
            LOGGER.error("Given type {} is not a sub-type of expected type {}", typeToDeserializeAs.get(), typeInfo.getType());
            return Optional.empty();
        }

        TypeHandler<T> runtimeTypeHandler = (TypeHandler<T>) typeSerializationLibrary.getTypeHandler(typeToDeserializeAs.get(), classLoaders)
                // To avoid compile errors in the orElseGet
                .map(typeHandler -> (TypeHandler) typeHandler)
                .orElseGet(() -> {
                    LOGGER.warn("Cannot find TypeHandler for runtime class {}, " +
                                    "deserializing as base class {}",
                            runtimeTypeName, typeInfo.getRawType().getName());
                    return delegateHandler;
                });

        PersistedData valueData = valueMap.get(VALUE_FIELD);

        return runtimeTypeHandler.deserialize(valueData);

    }

}
