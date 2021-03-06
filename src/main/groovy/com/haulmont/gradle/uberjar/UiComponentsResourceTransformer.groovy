package com.haulmont.gradle.uberjar


import groovy.xml.XmlUtil
import org.apache.commons.io.filefilter.WildcardFileFilter

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class UiComponentsResourceTransformer implements ResourceTransformer {
    protected final WildcardFileFilter wildcardsFilter

    UiComponentsResourceTransformer() {
        def newWildcards = new ArrayList<String>()
        newWildcards.add("cuba-ui-component.xml")
        newWildcards.add("META-INF/cuba-ui-component.xml")
        this.wildcardsFilter = new WildcardFileFilter(newWildcards)
    }

    @Override
    boolean canTransformEntry(String path) {
        return wildcardsFilter.accept(null, path.startsWith("/") ? path.substring(1) : path)
    }

    @Override
    void transform(Path toPath, Path fromPath) {
        if (Files.exists(toPath)) {
            def toXml = new XmlParser().parse(Files.newInputStream(toPath))
            def fromXml = new XmlParser().parse(Files.newInputStream(fromPath))

            fromXml.children().each { item ->
                toXml.append((Node) item.clone())
            }

            ByteArrayOutputStream byteOutput = new ByteArrayOutputStream()
            XmlUtil.serialize(toXml, byteOutput)
            Files.copy(new ByteArrayInputStream(byteOutput.toByteArray()), toPath, StandardCopyOption.REPLACE_EXISTING)

        } else {
            Files.copy(fromPath, toPath)
        }
    }
}
