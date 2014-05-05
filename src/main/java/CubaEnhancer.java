/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.LogFactory;
import org.apache.openjpa.conf.OpenJPAConfiguration;
import org.apache.openjpa.enhance.PCEnhancer;
import org.apache.openjpa.lib.util.Localizer;
import org.apache.openjpa.meta.ClassMetaData;
import org.apache.openjpa.meta.FieldMetaData;
import org.apache.openjpa.util.GeneralException;
import org.apache.openjpa.util.OpenJPAException;
import serp.bytecode.*;

/**
 * Enhances entity classes.
 */
public class CubaEnhancer implements PCEnhancer.AuxiliaryEnhancer {

    public static final String ENHANCED_TYPE = "com.haulmont.cuba.core.sys.CubaEnhanced";

    private static final String METAPROPERTY_ANNOTATION = "com.haulmont.chile.core.annotations.MetaProperty";
    private static final String TRANSIENT_ANNOTATION = "javax.persistence.Transient";

    protected org.apache.commons.logging.Log log;

    protected static final Localizer _loc = Localizer.forPackage(PCEnhancer.class);

    private BCClass _pc;
    private BCClass _managedType;

    public CubaEnhancer() {
        log = LogFactory.getLog(OpenJPAConfiguration.LOG_ENHANCE);
    }

    @Override
    public void run(BCClass bc, ClassMetaData meta) {
        _pc = bc;
        _managedType = bc;

        log.trace(String.format("CUBA specific enhancing for %s", _pc.getClassName()));

        try {
            if (_pc.isInterface())
                return;

            Class[] interfaces = _managedType.getDeclaredInterfaceTypes();
            for (Class anInterface : interfaces) {
                if (anInterface.getName().equals(ENHANCED_TYPE)) {
                    log.trace(String.format("Class %s already enchanced", _managedType.getType()));
                    return;
                }
            }

            enhanceSetters(meta);

            _pc.declareInterface(ENHANCED_TYPE);

        } catch (OpenJPAException ke) {
            throw ke;
        } catch (Exception e) {
            throw new GeneralException(_loc.get("enhance-error", _managedType.getType().getName(), e.getMessage()), e);
        }
    }

    private void enhanceSetters(ClassMetaData meta) throws NoSuchMethodException {
        BCMethod[] methods = _managedType.getDeclaredMethods();
        Code code;

        BCMethod[] propertyChangingMethods = _managedType.getMethods("propertyChanging",
                new Class[]{String.class, int.class, Object.class});
        boolean propertyChangingExists = propertyChangingMethods.length > 0;
        if (!propertyChangingExists)
            log.debug("Method propertyChanging() doesn't exist in " + _pc.getClassName());

        for (final BCMethod method : methods) {
            final String name = method.getName();
            if (method.isAbstract() || !name.startsWith("set") || method.getReturnType() != void.class)
                continue;

            code = method.getCode(false);

            final String fieldName = StringUtils.uncapitalize(name.replaceFirst("set", ""));

            if (propertyChangingExists) {
                FieldMetaData fieldMeta = meta.getDeclaredField(fieldName);
                int fieldIndex = fieldMeta != null ? fieldMeta.getDeclaredIndex() : -1;
                if (fieldIndex == -1) {
                    // enhance transient property
                    enhanceTransientSetter(code, method, fieldName);
                    continue;
                }

                // propertyChanging(<fieldName>, pcInheritedFieldCount + <fieldIndex>, <value>)
                code.aload().setThis();
                code.constant().setValue(fieldName);
                code.getstatic().setField("pcInheritedFieldCount", int.class);
                code.constant().setValue(fieldIndex);
                code.iadd();
                code.aload().setLocal(1);
                code.invokevirtual().setMethod("propertyChanging", void.class,
                        new Class[]{String.class, int.class, Object.class});
            }

            code.aload().setThis();
            code.invokevirtual().setMethod("get" + StringUtils.capitalize(fieldName), method.getParamTypes()[0],
                    new Class[]{});
            code.astore().setLocal(2);

            code.afterLast();
            Instruction vreturn = code.previous();

            code.afterLast();
            code.previous();
            /*
             find instruction pcSet + fieldName and invoke propertyChanged
             */
            code.beforeFirst();
            while (code.hasNext()) {
                Instruction inst = code.next();
                if (MethodInstruction.class.isAssignableFrom(inst.getClass())) {
                    if (((MethodInstruction) inst).getMethodName().equals("pcSet" + fieldName)) {
                        code.after(inst);
                        code.aload().setLocal(2);
                        code.aload().setLocal(1);
                        code.invokestatic().setMethod(ObjectUtils.class, "equals", boolean.class,
                                new Class[]{Object.class, Object.class});
                        IfInstruction ifne = code.ifne();
                        code.aload().setThis();
                        code.constant().setValue(fieldName);
                        code.aload().setLocal(2);
                        code.aload().setLocal(1);
                        code.invokevirtual().setMethod("propertyChanged", void.class,
                                new Class[]{String.class, Object.class, Object.class});
                        ifne.setTarget(vreturn);
                    }
                }
            }

            code.calculateMaxStack();
            code.calculateMaxLocals();
        }
    }

    protected void enhanceTransientSetter(Code code, BCMethod method, String fieldName) {
        BCField declaredField = _managedType.getDeclaredField(fieldName);
        if (declaredField == null) {
            return;
        }

        Annotations fieldAnnotations = declaredField.getDeclaredRuntimeAnnotations(false);
        if (fieldAnnotations == null
                || fieldAnnotations.getAnnotation(TRANSIENT_ANNOTATION) == null) {
            return;
        }

        Annotations methodAnnotations = method.getDeclaredRuntimeAnnotations(false);
        if (fieldAnnotations.getAnnotation(METAPROPERTY_ANNOTATION) == null
            && (methodAnnotations == null || methodAnnotations.getAnnotation(METAPROPERTY_ANNOTATION) == null)) {
            return;
        }

        String name = method.getName();

        LocalVariableTable table = code.getLocalVariableTable(false);
        if (table.getLocalVariable(StringUtils.lowerCase(name.replace("set", "") + "_local")) != null) {
            return;
        }

        code.aload().setThis();
        table.addLocalVariable(StringUtils.lowerCase(name.replace("set", "") + "_local"), method.getParamTypes()[0]).setStartPc(5);
        code.invokevirtual().setMethod("get" + StringUtils.capitalize(fieldName), method.getParamTypes()[0], new Class[]{});
        code.astore().setLocal(2);

        code.afterLast();
        Instruction vreturn = code.previous();
        code.before(vreturn);

        code.aload().setLocal(2);
        code.aload().setLocal(1);
        code.invokestatic().setMethod(ObjectUtils.class, "equals", boolean.class, new Class[]{Object.class, Object.class});
        IfInstruction ifne = code.ifne();
        code.aload().setThis();
        code.constant().setValue(fieldName);
        code.aload().setLocal(2);
        code.aload().setLocal(1);
        code.invokevirtual().setMethod("propertyChanged", void.class, new Class[]{String.class, Object.class, Object.class});

        ifne.setTarget(vreturn);

        code.calculateMaxStack();
        code.calculateMaxLocals();
    }

    @Override
    public boolean skipEnhance(BCMethod m) {
        return false;
    }
}
