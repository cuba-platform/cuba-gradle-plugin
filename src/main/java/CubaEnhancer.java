/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import javassist.*;
import javassist.bytecode.AnnotationsAttribute;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;

/**
 * Enhances entity classes.
 */
public class CubaEnhancer {

    public static final String ENHANCED_TYPE = "com.haulmont.cuba.core.sys.CubaEnhanced";
    public static final String ENHANCED_DISABLED_TYPE = "com.haulmont.cuba.core.sys.CubaEnhancingDisabled";

    private static final String METAPROPERTY_ANNOTATION = "com.haulmont.chile.core.annotations.MetaProperty";

    private Log log = LogFactory.getLog(CubaEnhancer.class);

    private ClassPool pool;
    private String outputDir;

    public CubaEnhancer(ClassPool pool, String outputDir) throws NotFoundException, CannotCompileException {
        this.pool = pool;
        this.outputDir = outputDir;
    }

    public void run(String className) {
        try {
            CtClass cc = pool.get(className);

            for (CtClass intf : cc.getInterfaces()) {
                if (intf.getName().equals(ENHANCED_TYPE) || intf.getName().equals(CubaEnhancer.ENHANCED_DISABLED_TYPE)) {
                    log.info("CubaEnhancer: " + className + " has already been enhanced or should not be enhanced at all");
                    return;
                }
            }

            log.info("CubaEnhancer: enhancing " + className);
            enhanceSetters(cc);

            cc.addInterface(pool.get("com.haulmont.cuba.core.sys.CubaEnhanced"));
            cc.writeFile(outputDir);
        } catch (NotFoundException | IOException | CannotCompileException | ClassNotFoundException e) {
            throw new RuntimeException("Error enhancing class " + className + ": " + e, e);
        }
    }

    private void enhanceSetters(CtClass ctClass) throws NotFoundException, CannotCompileException, ClassNotFoundException {
        for (CtMethod ctMethod : ctClass.getDeclaredMethods()) {
            final String name = ctMethod.getName();
            if (Modifier.isAbstract(ctMethod.getModifiers())
                    || !name.startsWith("set")
                    || ctMethod.getReturnType() != CtClass.voidType
                    || ctMethod.getParameterTypes().length != 1)
                continue;

            String fieldName = StringUtils.uncapitalize(name.substring(3));

            // check if the setter is for a persistent or transient property
            CtMethod persistenceMethod = null;
            for (CtMethod method : ctClass.getDeclaredMethods()) {
                if (method.getName().equals("_persistence_set_" + fieldName)) {
                    persistenceMethod = method;
                    break;
                }
            }
            if (persistenceMethod == null) {
                // can be a transient property
                CtField ctField = null;
                CtField[] declaredFields = ctClass.getDeclaredFields();
                for (CtField field : declaredFields) {
                    if (field.getName().equals(fieldName)) {
                        ctField = field;
                        break;
                    }
                }
                if (ctField == null)
                    continue; // no field
                // check if the field is annotated with @MetaProperty
                // cannot use ctField.getAnnotation() because of problem with classpath in child projects
                AnnotationsAttribute annotationsAttribute =
                        (AnnotationsAttribute) ctField.getFieldInfo().getAttribute(AnnotationsAttribute.visibleTag);
                if (annotationsAttribute == null || annotationsAttribute.getAnnotation(METAPROPERTY_ANNOTATION) == null)
                    continue;
            }

            CtClass setterParamType = ctMethod.getParameterTypes()[0];

            if (setterParamType.isPrimitive()) {
                throw new IllegalStateException(
                        String.format("Unable to enhance field %s.%s with primitive type %s. Use type %s.",
                                ctClass.getName(), fieldName,
                                setterParamType.getSimpleName(), StringUtils.capitalize(setterParamType.getSimpleName())));
            }

            ctMethod.addLocalVariable("__prev", setterParamType);

            ctMethod.insertBefore(
                    "__prev = this.get" + StringUtils.capitalize(fieldName) + "();"
            );

            ctMethod.insertAfter(
                    "if (!java.util.Objects.equals(__prev, $1)) {" +
                    "  this.propertyChanged(\"" + fieldName + "\", __prev, $1);" +
                    "}"
            );
        }
    }
}
