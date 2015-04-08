/*
 * Copyright (c) 2008-2013 Haulmont. All rights reserved.
 * Use is subject to license terms, see http://www.cuba-platform.com/license for details.
 */

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.openjpa.enhance.AsmAdaptor;
import serp.bytecode.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author krivopustov
 * @version $Id$
 */
public class CubaTransientEnhancer {

    private static Log log = LogFactory.getLog(CubaTransientEnhancer.class);

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Error: metadata.xml is not specified");
            return;
        }

        try {
            Project project = new Project();
            String outputDir = null;
            List<BCClass> classes = new ArrayList<>();
            int i = 0;
            while (i < args.length) {
                if ("-o".equals(args[i])) {
                    if (i < args.length - 2) {
                        outputDir = args[i + 1];
                        i++;
                    }
                } else if (args[i].startsWith("-o")) {
                    outputDir = args[i].substring(3);
                } else {
                    try {
                        Class<?> aClass = Class.forName(args[i]);
                        classes.add(project.loadClass(aClass));
                    } catch (ClassNotFoundException e) {
                        log.error("Unable to load class " + args[i], e);
                        System.exit(-1);
                    }
                }
                i++;
            }

            CubaTransientEnhancer enhancer = new CubaTransientEnhancer();
            iteration:
            for (BCClass cl : classes) {
                Class[] interfaces = cl.getDeclaredInterfaceTypes();
                for (Class anInterface : interfaces) {
                    if (anInterface.getName().equals(CubaEnhancer.ENHANCED_TYPE)) {
                        log.trace(String.format("Class %s is already enchanced", cl.getType()));
                        continue iteration;
                    }
                }
                log.info("enhancing: " + cl.getName());
                enhancer.enhanceSetters(cl);
                cl.declareInterface(CubaEnhancer.ENHANCED_TYPE);
                if (!StringUtils.isEmpty(outputDir)) {
                    File file = getOutputFile(cl, outputDir);
                    cl.write(file);
                    AsmAdaptor.write(cl, file); // see https://issues.apache.org/jira/browse/OPENJPA-2085
                } else {
                    cl.write();
                    AsmAdaptor.write(cl); // see https://issues.apache.org/jira/browse/OPENJPA-2085
                }
            }
        } catch (IOException e) {
            log.error("Error", e);
            System.exit(-1);
        }
    }

    private static File getOutputFile(BCClass cl, String dir) {
        File file = new File(dir, cl.getName().replace('.', '/') + ".class");
        file.getParentFile().mkdirs();
        return file;
    }

    private void enhanceSetters(BCClass editingClass) {
        BCMethod[] methods = editingClass.getDeclaredMethods();
        Code code;
        for (BCMethod method : methods) {
            String name = method.getName();
            if (method.isAbstract() || (!name.startsWith("set")) || (method.getReturnType() != void.class))
                continue;
            code = method.getCode(false);
            LocalVariableTable table =  code.getLocalVariableTable(false);
            if (table.getLocalVariable(StringUtils.lowerCase(name.replaceFirst("set","")+"_local"))!=null){
                return;
            }

            String fieldName = StringUtils.uncapitalize(name.replaceFirst("set",""));
            code.aload().setThis();
            table.addLocalVariable(StringUtils.lowerCase(name.replaceFirst("set","")+"_local"),method.getParamTypes()[0]).setStartPc(5);
            code.invokevirtual().setMethod("get" + StringUtils.capitalize(fieldName) , method.getParamTypes()[0], new Class[]{});
            code.astore().setLocal(2);

            code.afterLast();
            Instruction vreturn = code.previous();
            code.before(vreturn);

            code.aload().setLocal(2);
            code.aload().setLocal(1);
            code.invokestatic().setMethod(ObjectUtils.class, "equals", boolean.class, new Class[]{Object.class,Object.class});
            IfInstruction ifne = code.ifne();
            code.aload().setThis();
            code.constant().setValue(fieldName);
            code.aload().setLocal(2);
            code.aload().setLocal(1);
            code.invokevirtual().setMethod("propertyChanged", void.class, new Class[]{String.class,Object.class,Object.class});

            ifne.setTarget(vreturn);

            code.calculateMaxStack();
            code.calculateMaxLocals();
        }
    }
}