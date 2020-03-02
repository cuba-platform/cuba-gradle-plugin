/*
 * Copyright (c) 2008-2020 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.haulmont.gradle.enhance;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.Loader;
import javassist.NotFoundException;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.MethodUtils;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Creates entity projection implementation based on interface and base class.
 * Idea is to delegate all method invocations form the projection interface to the underlying entity
 * transforming (wrapping and unwrapping to entity projection views) parameters and result if needed.
 */
public class CubaEntityProjectionWrapperCreator {

    protected final org.gradle.api.logging.Logger log;

    public static final String BASE_INTERFACE_NAME = "com.haulmont.addons.cuba.entity.projections.BaseProjection";
    public static final String BASE_CLASS_NAME = "com.haulmont.addons.cuba.entity.projections.BaseProjectionImpl";
    public static final String WRAPPER_CLASS_NAME = "com.haulmont.addons.cuba.entity.projections.factory.EntityProjectionWrapper";
    public static final String WRAPPING_LIST_CLASS_NAME = "com.haulmont.addons.cuba.entity.projections.factory.WrappingList";

    protected final ClassPool pool;
    protected final String outputDir;

    private final Class<?> baseInterfaceClass;
    private final Class<?> baseImplementationClass;
    private final Loader loader;

    /**
     * Creates new instance of the creator. Fails if projection interface or its abstract implementation is not found.
     * @param pool class pool to be used by JavaAssist.
     * @param outputDir build output dir, all projection implementations will be written to it.
     * @param log Gradle logger.
     */
    public CubaEntityProjectionWrapperCreator(ClassPool pool, String outputDir, org.gradle.api.logging.Logger log) {
        this.pool = pool;
        this.outputDir = outputDir;
        this.log = log;
        this.loader = new Loader(this.pool);
        try {
            baseInterfaceClass = loader.loadClass(BASE_INTERFACE_NAME);
            baseImplementationClass = loader.loadClass(BASE_CLASS_NAME);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot find base classes to generate wrapper implementation", e);
        }
    }

    /**
     * Creates projection implementation instance for a projection interface. Works only for interfaces
     * that extend base projection interface.
     * @param effectiveProjectionInterfaceName - projection interface class name.
     */
    public void run(String effectiveProjectionInterfaceName) {
        if (effectiveProjectionInterfaceName == null) {
            log.debug("Cannot generate entity projection for null interface");
            return;
        }
        try {
            Class<?> effectiveProjectionInterface = loader.loadClass(effectiveProjectionInterfaceName);
            if (!effectiveProjectionInterface.isInterface()
                    || !baseInterfaceClass.isAssignableFrom(effectiveProjectionInterface)
                    || baseInterfaceClass == effectiveProjectionInterface
            ) {
                log.debug("Cannot generate entity projection for class {}", effectiveProjectionInterface);
                return;
            }
            log.debug("Generating Entity Projection for: {}, base interface class: {}", effectiveProjectionInterface, baseInterfaceClass.getName());
            CtClass wrapperImplementation = createWrapperImplementation(effectiveProjectionInterface, createWrapperClassName(effectiveProjectionInterface));
            log.debug("Writing implementation {} to {}", wrapperImplementation.getName(), outputDir);
            wrapperImplementation.writeFile(outputDir);
        } catch (Exception e) {
            throw new RuntimeException(String.format("Cannot generate wrapper implementation for interface %s", effectiveProjectionInterfaceName), e);
        }
    }

    /**
     * Creates projection implementation class name.
     * TODO: Should we think about adding a parameter instead of hardcoded string?
     * @param effectiveProjection - projection interface class instance.
     * @return - name for projection implementation class.
     */
    private static String createWrapperClassName(Class<?> effectiveProjection) {
        return String.format("%sWrapperImpl", effectiveProjection.getName());
    }

    /**
     * Main method that generates projection implementation using JavaAssist.
     * @param projectionInterface projection interface class.
     * @param wrapperName projection implementation class name.
     * @return projection implementation class as JavaAssist class instance.
     * @throws NotFoundException if projection abstract implementation is not found.
     * @throws CannotCompileException if generated projection implementation cannot be compiled.
     * @throws IllegalArgumentException if something went wrong during method invocation delegation.
     */
    private CtClass createWrapperImplementation(Class<?> projectionInterface, String wrapperName) throws NotFoundException, CannotCompileException {
        CtClass baseClass = pool.get(BASE_CLASS_NAME); //Abstract projection implementation
        CtClass projectionEntityClass = pool.get(getProjectionEntityClassName(projectionInterface)); //Getting underlying entity class
        CtClass viewIf = pool.get(projectionInterface.getName()); //Projection interface class


        if (pool.getOrNull(wrapperName) != null ) {
            return pool.get(wrapperName);
        }

        CtClass wrapperClass = pool.makeClass(wrapperName, baseClass);
        wrapperClass.addInterface(viewIf);

        //We need to generate strongly typed getOrigin() method in every generated implementation due to type erasure.
        CtMethod getOrigin = CtNewMethod.make(projectionEntityClass,
                "getOrigin",
                null,
                null,
                "return (" + projectionEntityClass.getName() + ")entity;",
                wrapperClass);
        wrapperClass.addMethod(getOrigin);

        //Get all method that will be delegated to the underlying entity
        List<Method> entityProjectionMethods = getEntityProjectionMethods(projectionInterface);

        //Delegate invocation. We need to throw unchecked exception from lambdas.
        entityProjectionMethods.forEach(m -> {
            try {
                wrapperClass.addMethod(createDelegateMethod(wrapperClass, m, projectionEntityClass));
            } catch (NotFoundException | CannotCompileException | ClassNotFoundException e) {
                throw new IllegalArgumentException("Cannot add method " + m.getName() + " to wrapper class " + wrapperName, e);
            }
        });
        return wrapperClass;
    }

    /**
     * Creates list of methods that will be delegated to the underlying entity. We filter out
     * methods implemented in the abstract projection implementation and default methods from the projection
     * interface.
     * @param projectionInterface projection interface that is used for implementation.
     * @return method list.
     */
    private List<Method> getEntityProjectionMethods(Class<?> projectionInterface) {
        return Arrays.stream(projectionInterface.getMethods())
                .filter(method ->
                        MethodUtils.getMatchingMethod(baseImplementationClass, method.getName(), method.getParameterTypes()) == null
                                && !method.isDefault())
                .collect(Collectors.toList());
    }

    /**
     * Creates method that delegates invocation to the underlying entity. We also add some code to deal with projections
     * in input parameters and wrap result to projection if needed.
     * @param wrapper projection implementation class.
     * @param m projection interface method, its invocation will be delegated.
     * @param wrappingEntityClass underlying entity class.
     * @return method that invokes entity's method.
     * @throws NotFoundException if some classes are not loaded to the JavaAssist's class pool.
     * @throws CannotCompileException if method's body text is invalid.
     * @throws ClassNotFoundException if some classes are not loaded to the JavaAssist's class pool.
     */
    private CtMethod createDelegateMethod(CtClass wrapper, Method m, CtClass wrappingEntityClass) throws NotFoundException, CannotCompileException, ClassNotFoundException {
        Class<?>[] parameterTypes = m.getParameterTypes();

        //Create parameters list as string. Here we add parameters unwrapping if needed.
        List<String> paramDelegatesList = IntStream.range(0, parameterTypes.length)
                .mapToObj(i -> createParameterDelegateString(i + 1, parameterTypes[i]))
                .collect(Collectors.toList());

        //Generating parameters string for method.
        String paramDelegatesInvoke = String.join(",", paramDelegatesList);

        //Delegating invocation to the underlying entity.
        String methodDelegateInvocation = "instance." + m.getName() + "(" + paramDelegatesInvoke + ")";

        //Updating delegation method body according to result type.
        String body = appendResultReturnCode(m, methodDelegateInvocation);

        CtClass[] paramTypes = pool.get(Arrays.stream(parameterTypes)
                .map(Class::getName)
                .toArray(String[]::new));

        CtClass[] exceptionTypes = pool.get(Arrays.stream(m.getExceptionTypes())
                .map(Class::getName)
                .toArray(String[]::new));

        return CtNewMethod.make(m.getModifiers(),
                pool.get(m.getReturnType().getName()),
                m.getName(),
                paramTypes,
                exceptionTypes,
                "{doReload();\n" //We need to check if entity reload is needed - it is lazy reloading
                        + wrappingEntityClass.getName() + " instance = getOrigin();"
                        + body
                        + "}",
                wrapper);
    }

    /**
     * For each parameter returns either itself or unwrapping invocation if parameter type is projection.
     * <pre>
     *     projectionParam -> (EntityClass)(projectionParam.getOrigin())
     * </pre>
     * @param parameterNum
     * @param parameterType
     * @return
     */
    private String createParameterDelegateString(int parameterNum, Class<?> parameterType) {
        if (baseInterfaceClass.isAssignableFrom(parameterType)) {//We need to unwrap parameter before passing it to underlying entity
            String paramTypeName = getProjectionEntityClassName(parameterType);
            return "(" + paramTypeName + ")($" + parameterNum + ".getOrigin())";
        } else {
            return "$" + parameterNum;
        }
    }

    /**
     * Depending on result type, adding result processing to the method body.
     * @param m projection interface method.
     * @param body delegating method body.
     * @return updated method body.
     * @throws ClassNotFoundException if we cannot define generic parameter for the collection return type.
     */
    private String appendResultReturnCode(Method m, String body) throws ClassNotFoundException {
        Class<?> returnType = m.getReturnType();
        String returnTypeName = returnType.getName();
        if (Collection.class.isAssignableFrom(returnType)) { //Need to wrap entities into list and return it.
            Class<?> collectionGenericType = getMethodReturnType(m);
            return "\nreturn new " + WRAPPING_LIST_CLASS_NAME + "(" + body + ", " + collectionGenericType.getName() + ".class);";
        } else if (baseInterfaceClass.isAssignableFrom(returnType)) { //Just wrap result into projection.
            return "\nreturn " + WRAPPER_CLASS_NAME + ".wrap(" + body + ", " + returnTypeName + ".class);";
        } else if (!returnType.equals(Void.TYPE)) { //Result should not be wrapped.
            return "\nreturn " + body + ";";
        } else {
            return body + ";"; //Void method, usually setter. Should not return anything.
        }
    }

    /**
     * Method that gets type parameter for projection interface, taking class hierarchy into account.
     * @param effectiveProjection projection interface class.
     * @return type parameter class name.
     */
    private static String getProjectionEntityClassName(Class<?> effectiveProjection) {

        List<Class<?>> implementedInterfaces = ClassUtils.getAllInterfaces(effectiveProjection);
        implementedInterfaces.add(effectiveProjection);

        for (Class<?> intf : implementedInterfaces) {
            Set<ParameterizedType> candidateTypes = Arrays.stream(intf.getGenericInterfaces())
                    .filter(type -> type instanceof ParameterizedType)
                    .map(type -> ((ParameterizedType) type))
                    .filter(parameterizedType -> BASE_INTERFACE_NAME.equals(parameterizedType.getRawType().getTypeName()))
                    .collect(Collectors.toSet());

            if (candidateTypes.size() == 1) {
                ParameterizedType baseProjectionIntf = candidateTypes.iterator().next();
                Type entityType = Arrays.asList(baseProjectionIntf.getActualTypeArguments()).get(0);
                return entityType.getTypeName();
            }
        }
        throw new IllegalArgumentException(String.format("Cannot get generic entity type parameter for projection %s", effectiveProjection.getName()));
    }


    /**
     * Returns actual method return type or collection parameter type for one-to-many
     * relation attributes. Used for building CUBA views based on entity views.
     *
     * @param viewMethod method to be used in CUBA view.
     * @return type that will be used in CUBA view.
     */
    public Class<?> getMethodReturnType(Method viewMethod) throws ClassNotFoundException {
        Class<?> returnType = viewMethod.getReturnType();
        if (Collection.class.isAssignableFrom(returnType)) {
            Type genericReturnType = viewMethod.getGenericReturnType();
            if (genericReturnType instanceof ParameterizedType) {
                ParameterizedType type = (ParameterizedType) genericReturnType;
                List<Class<?>> collectionTypes = new ArrayList<>();
                for (Type t : type.getActualTypeArguments()) {
                    Class<?> aClass = loader.loadClass(t.getTypeName());
                    collectionTypes.add(aClass);
                }
                //TODO make this code a bit more accurate
                if (collectionTypes.stream().anyMatch(baseInterfaceClass::isAssignableFrom)) {
                    return collectionTypes.stream().filter(baseInterfaceClass::isAssignableFrom).findFirst().orElseThrow(RuntimeException::new);
                } else {
                    return collectionTypes.stream().findFirst().orElseThrow(RuntimeException::new);
                }
            }
        }
        log.trace("Method {} return type {}", viewMethod.getName(), returnType);
        return returnType;
    }
}
