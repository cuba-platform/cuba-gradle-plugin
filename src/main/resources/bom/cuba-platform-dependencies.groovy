/*
 * Copyright (c) 2008-2016 Haulmont.
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
 *
 */

def springVersion = '4.3.3.RELEASE'
def springSecurityVersion = '4.1.3.RELEASE'
def thymeleafVersion = '3.0.2.RELEASE'
def jacksonVersion = '2.8.0'
def vaadinVersion = '7.7.3.cuba.2'
def googleGwtVersion='2.8.0'

def luceneVersion = '5.3.0'
def morphologyVersion = '1.1'

def activitiVersion = '5.20.0'
def modelerVersion = '5.20.0.cuba.18'

def yargVersion = '1.0.67'

[
  'org.apache.tomcat:tomcat' : '8.0.35',
  'org.apache.tomcat:tomcat-servlet-api' : '8.0.26',

  'org.postgresql:postgresql' : '9.4-1201-jdbc41',
  'org.hsqldb:hsqldb' : '2.3.3',
  'net.sourceforge.jtds:jtds' : '1.3.1',
  'mysql:mysql-connector-java' : '5.1.38',

  'commons-logging:commons-logging' : '1.2',
  'commons-lang:commons-lang' : '2.6',
  'commons-collections:commons-collections' : '3.2.1',
  'commons-io:commons-io' : '2.4',
  'commons-codec:commons-codec' : '1.10',
  'commons-cli:commons-cli' : '1.3.1',
  'commons-fileupload:commons-fileupload' : '1.3.2',

  'org.apache.httpcomponents:httpclient' : '4.5.2',
  'org.apache.httpcomponents:httpmime' : '4.5.2',
  'org.apache.httpcomponents:httpcore' : '4.4.5',
  'org.apache.commons:commons-pool2': '2.4.2',
  'org.apache.commons:commons-dbcp2': '2.1.1',
  'org.apache.commons:commons-compress' : '1.9',

  'com.haulmont.thirdparty:eclipselink' : '2.6.2.cuba11',
  'com.haulmont.thirdparty:xstream' : '1.4.2.20120702',
  'com.haulmont.thirdparty:glazedlists' : '1.9.20110801',
  'com.haulmont.thirdparty:swingx-core' : '1.6.5-1.cuba.0',
  'com.haulmont.thirdparty:poi' : '3.12.cuba.1',
  'com.haulmont.thirdparty:popupbutton' : '2.5.2.cuba.3',
  'org.vaadin.addons:aceeditor' : '0.8.14',
  'org.vaadin.addons:contextmenu' : '4.5',
  'org.vaadin.addons:dragdroplayouts' : '1.3.2.cuba.1',
  'com.haulmont.thirdparty:jbpm' : '4.4.20130109',
  'com.haulmont.thirdparty:yui' : '2.8.1',

  'com.esotericsoftware:kryo-shaded' : '4.0.0',
  'de.javakaffee:kryo-serializers' : '0.38',

  'asm:asm' : '3.2',
  'dom4j:dom4j' : '1.6.1',
  'org.freemarker:freemarker' : '2.3.23',
  'org.jsoup:jsoup' : '1.8.3',
  'com.google.code.gson:gson' : '2.5',
  'aopalliance:aopalliance' : '1.0',
  'org.codehaus.groovy:groovy-all' : '2.4.4',
  'ch.qos.logback:logback-classic' : '1.1.3',
  'org.slf4j:log4j-over-slf4j' : '1.7.12',
  'org.json:json' : '20140107',
  'com.sun.mail:javax.mail' : '1.5.4',
  'org.perf4j:perf4j' : '0.9.16',
  'com.google.code.findbugs:jsr305' : '3.0.0',
  'javax:javaee-api' : '7.0',
  'antlr:antlr' : '2.7.7',
  'org.antlr:antlr-runtime' : '3.5.2',
  'com.google.guava:guava' : '18.0',
  'org.eclipse.persistence:javax.persistence' : '2.1.0',
  'org.eclipse.persistence:commonj.sdo' : '2.1.1',
  'org.glassfish:javax.json' : '1.0.4',
  'javax.validation:validation-api' : '1.1.0.Final',
  'xpp3:xpp3_min' : '1.1.4c',
  'xmlpull:xmlpull' : '1.1.3.1',
  'org.ocpsoft.prettytime:prettytime-nlp' : '4.0.0.Final',
  'org.jgroups:jgroups' : '3.6.7.Final',
  'org.aspectj:aspectjrt' : '1.8.6',
  'org.aspectj:aspectjweaver' : '1.8.6',
  'org.mybatis:mybatis' : '3.2.7',
  'org.mybatis:mybatis-spring' : '1.2.3',
  'org.jmockit:jmockit' : '1.15',
  'junit:junit' : '4.12',
  'org.testng:testng' : '6.8.8',
  'com.vaadin.external.google:guava-vaadin-shaded' : '16.0.1.vaadin1',
  'com.miglayout:miglayout-swing' : '4.2',
  'com.fifesoft:rsyntaxtextarea' : '2.5.6',
  'de.odysseus.juel:juel' : '2.1.0',
  'org.javassist:javassist' : '3.20.0-GA',
  'org.hibernate:hibernate-core' : '3.3.1.GA',

  'com.vaadin:vaadin-shared' : vaadinVersion,
  'com.vaadin:vaadin-server' : vaadinVersion,
  'com.vaadin:vaadin-client' : vaadinVersion,
  'com.vaadin:vaadin-client-compiler' : vaadinVersion,
  'com.vaadin:vaadin-themes' : vaadinVersion,
  'com.vaadin:vaadin-push' : vaadinVersion,
  'com.google.gwt:gwt-elemental' : googleGwtVersion,
  'com.google.gwt:gwt-dev' : googleGwtVersion,

  'org.springframework:spring-core' : springVersion,
  'org.springframework:spring-beans' : springVersion,
  'org.springframework:spring-context' : springVersion,
  'org.springframework:spring-web' : springVersion,
  'org.springframework:spring-context-support' : springVersion,
  'org.springframework:spring-orm' : springVersion,
  'org.springframework:spring-tx' : springVersion,
  'org.springframework:spring-webmvc' : springVersion,
  'org.springframework:spring-jdbc' : springVersion,

  'org.springframework.security:spring-security-core' : springSecurityVersion,
  'org.springframework.security:spring-security-web' : springSecurityVersion,
  'org.springframework.security:spring-security-config' : springSecurityVersion,
  'org.springframework.security:spring-security-taglibs' : springSecurityVersion,

  'org.springframework.security.oauth:spring-security-oauth2' : '2.0.11.RELEASE',
  'org.springframework.ldap:spring-ldap-core' : '2.0.4.RELEASE',

  'org.thymeleaf:thymeleaf' : thymeleafVersion,
  'org.thymeleaf:thymeleaf-spring4' : thymeleafVersion,
  'org.thymeleaf.extras:thymeleaf-extras-springsecurity4' : '3.0.0.RELEASE',

  'com.fasterxml.jackson.core:jackson-databind' : jacksonVersion,
  'com.fasterxml.jackson.dataformat:jackson-dataformat-yaml' : jacksonVersion,
  'org.codehaus.jackson:jackson-mapper-asl' : '1.9.13',

  'org.apache.lucene:lucene-core' : luceneVersion,
  'org.apache.lucene:lucene-analyzers-common' : luceneVersion,
  'org.apache.lucene:lucene-backward-codecs' : luceneVersion,

  'com.haulmont.thirdparty.lucene.morphology:morphology-ru' : morphologyVersion,
  'com.haulmont.thirdparty.lucene.morphology:morphology-en' : morphologyVersion,
  'com.haulmont.thirdparty.lucene.morphology:morph' : morphologyVersion,

  'org.apache.tika:tika-parsers' : '1.9',

  'org.activiti:activiti-engine' : activitiVersion,
  'org.activiti:activiti-spring' : activitiVersion,
  'org.activiti:activiti-json-converter' : modelerVersion,
  'com.haulmont.bpm:cuba-modeler' : modelerVersion,

  'com.haulmont.thirdparty:googlemaps' : '1.0.2.CUBA.9',

  'com.haulmont.yarg:yarg' : yargVersion,
  'com.haulmont.yarg:yarg-api' : yargVersion
]