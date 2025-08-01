/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.pipeline.maven.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.FilePath;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.pipeline.maven.MavenArtifact;
import org.jenkinsci.plugins.pipeline.maven.MavenDependency;
import org.jenkinsci.plugins.pipeline.maven.MavenSpyLogProcessor;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
public class XmlUtils {
    private static final Logger LOGGER = Logger.getLogger(XmlUtils.class.getName());

    public static MavenArtifact newMavenArtifact(Element artifactElt) {
        MavenArtifact mavenArtifact = new MavenArtifact();
        loadMavenArtifact(artifactElt, mavenArtifact);

        return mavenArtifact;
    }

    public static MavenDependency newMavenDependency(Element dependencyElt) {
        MavenDependency dependency = new MavenDependency();
        loadMavenArtifact(dependencyElt, dependency);
        dependency.setScope(dependencyElt.getAttribute("scope"));
        dependency.optional = Boolean.parseBoolean(dependencyElt.getAttribute("optional"));

        return dependency;
    }

    private static void loadMavenArtifact(Element artifactElt, MavenArtifact mavenArtifact) {
        mavenArtifact.setGroupId(artifactElt.getAttribute("groupId"));
        mavenArtifact.setArtifactId(artifactElt.getAttribute("artifactId"));
        mavenArtifact.setVersion(artifactElt.getAttribute("version"));
        mavenArtifact.setBaseVersion(artifactElt.getAttribute("baseVersion"));
        if (mavenArtifact.getBaseVersion() == null
                || mavenArtifact.getBaseVersion().isEmpty()) {
            mavenArtifact.setBaseVersion(mavenArtifact.getVersion());
        }
        String snapshot = artifactElt.getAttribute("snapshot");
        mavenArtifact.setSnapshot(
                snapshot != null && !snapshot.trim().isEmpty()
                        ? Boolean.parseBoolean(artifactElt.getAttribute("snapshot"))
                        : mavenArtifact.getBaseVersion().contains("SNAPSHOT"));
        mavenArtifact.setType(artifactElt.getAttribute("type"));
        if (mavenArtifact.getType() == null || mavenArtifact.getType().isEmpty()) {
            // workaround: sometimes we use "XmlUtils.newMavenArtifact()" on "project" elements, in this case,
            // "packaging" is defined but "type" is not defined
            // we should  probably not use "MavenArtifact"
            mavenArtifact.setType(artifactElt.getAttribute("packaging"));
        }
        mavenArtifact.setClassifier(
                artifactElt.hasAttribute("classifier") ? artifactElt.getAttribute("classifier") : null);
        mavenArtifact.setExtension(artifactElt.getAttribute("extension"));
    }

    /*
     <plugin executionId="default-test" goal="test" groupId="org.apache.maven.plugins" artifactId="maven-surefire-plugin" version="2.19.1">
    */
    public static MavenSpyLogProcessor.PluginInvocation newPluginInvocation(Element pluginInvocationElt) {
        MavenSpyLogProcessor.PluginInvocation pluginInvocation = new MavenSpyLogProcessor.PluginInvocation();
        pluginInvocation.groupId = pluginInvocationElt.getAttribute("groupId");
        pluginInvocation.artifactId = pluginInvocationElt.getAttribute("artifactId");
        pluginInvocation.version = pluginInvocationElt.getAttribute("version");
        pluginInvocation.goal = pluginInvocationElt.getAttribute("goal");
        pluginInvocation.executionId = pluginInvocationElt.getAttribute("executionId");
        return pluginInvocation;
    }

    @NonNull
    public static Element getUniqueChildElement(@NonNull Element element, @NonNull String childElementName) {
        Element child = getUniqueChildElementOrNull(element, childElementName);
        if (child == null) {
            throw new IllegalStateException("No <" + childElementName + "> element found");
        }
        return child;
    }

    @Nullable
    public static Element getUniqueChildElementOrNull(@NonNull Element element, String... childElementName) {
        Element result = element;
        for (String childEltName : childElementName) {
            List<Element> childElts = getChildrenElements(result, childEltName);
            if (childElts.size() == 0) {
                return null;
            } else if (childElts.size() > 1) {
                throw new IllegalStateException("More than 1 (" + childElts.size() + ") elements <" + childEltName
                        + "> found in " + toString(element));
            }

            result = childElts.get(0);
        }
        return result;
    }

    @NonNull
    public static List<Element> getChildrenElements(@NonNull Element element, @NonNull String childElementName) {
        NodeList childElts = element.getChildNodes();
        List<Element> result = new ArrayList<>();

        for (int i = 0; i < childElts.getLength(); i++) {
            Node node = childElts.item(i);
            if (node instanceof Element && node.getNodeName().equals(childElementName)) {
                result.add((Element) node);
            }
        }

        return result;
    }

    @NonNull
    public static String toString(@Nullable Node node) {
        try {
            StringWriter out = new StringWriter();
            Transformer identityTransformer = TransformerFactory.newInstance().newTransformer();
            identityTransformer.transform(new DOMSource(node), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            LOGGER.log(Level.WARNING, "Exception dumping node " + node, e);
            return e.toString();
        }
    }

    @NonNull
    public static List<Element> getExecutionEvents(@NonNull Element mavenSpyLogs, String... expectedType) {

        Set<String> expectedTypes = new HashSet<>(Arrays.asList(expectedType));
        List<Element> result = new ArrayList<>();
        for (Element element : getChildrenElements(mavenSpyLogs, "ExecutionEvent")) {
            if (expectedTypes.contains(element.getAttribute("type"))) {
                result.add(element);
            }
        }
        return result;
    }

    /*
    <RepositoryEvent type="ARTIFACT_DEPLOYED" class="org.eclipse.aether.RepositoryEvent" _time="2018-02-11 16:18:26.505">
        <artifact extension="jar" file="/path/to/my-project-workspace/target/my-jar-0.5-SNAPSHOT.jar" baseVersion="0.5-SNAPSHOT" groupId="com.example" classifier="" artifactId="my-jar" id="com.example:my-jar:jar:0.5-20180211.151825-18" version="0.5-20180211.151825-18" snapshot="true"/>
        <repository layout="default" id="nexus.beescloud.com" url="https://nexus.beescloud.com/content/repositories/snapshots/"/>
    </RepositoryEvent>
    <ExecutionEvent type="ProjectSucceeded" class="org.apache.maven.lifecycle.internal.DefaultExecutionEvent" _time="2018-02-11 16:18:30.971">
        <project baseDir="/path/to/my-project-workspace" file="/path/to/my-project-workspace/pom.xml" groupId="com.example" name="my-jar" artifactId="my-jar" version="0.5-SNAPSHOT">
          <build sourceDirectory="/path/to/my-project-workspace/src/main/java" directory="/path/to/my-project-workspace/target"/>
        </project>
        <no-execution-found/>
        <artifact extension="jar" baseVersion="0.5-SNAPSHOT" groupId="com.example" artifactId="my-jar" id="com.example:my-jar:jar:0.5-SNAPSHOT" type="jar" version="0.5-20180211.151825-18" snapshot="true">
          <file>/path/to/my-project-workspace/target/my-jar-0.5-SNAPSHOT.jar</file>
        </artifact>
        <attachedArtifacts/>
    </ExecutionEvent>
     */
    @NonNull
    public static List<Element> getArtifactDeployedEvents(@NonNull Element mavenSpyLogs) {
        List<Element> elements = new ArrayList<>();

        NodeList nodes = mavenSpyLogs.getChildNodes();

        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element) {
                Element element = (Element) node;
                if (StringUtils.equals(element.getNodeName(), "RepositoryEvent")) {
                    Attr type = element.getAttributeNode("type");
                    if (null != type && StringUtils.equals(type.getValue(), "ARTIFACT_DEPLOYED")) {
                        elements.add(element);
                    }
                }
            }
        }
        return elements;
    }

    /*
    <RepositoryEvent type="ARTIFACT_DEPLOYED" class="org.eclipse.aether.RepositoryEvent" _time="2018-02-11 16:18:26.505">
        <artifact extension="jar" file="/path/to/my-project-workspace/target/my-jar-0.5-SNAPSHOT.jar" baseVersion="0.5-SNAPSHOT" groupId="com.example" classifier="" artifactId="my-jar" id="com.example:my-jar:jar:0.5-20180211.151825-18" version="0.5-20180211.151825-18" snapshot="true"/>
        <repository layout="default" id="nexus.beescloud.com" url="https://nexus.beescloud.com/content/repositories/snapshots/"/>
    </RepositoryEvent>
    <ExecutionEvent type="ProjectSucceeded" class="org.apache.maven.lifecycle.internal.DefaultExecutionEvent" _time="2018-02-11 16:18:30.971">
        <project baseDir="/path/to/my-project-workspace" file="/path/to/my-project-workspace/pom.xml" groupId="com.example" name="my-jar" artifactId="my-jar" version="0.5-SNAPSHOT">
          <build sourceDirectory="/path/to/my-project-workspace/src/main/java" directory="/path/to/my-project-workspace/target"/>
        </project>
        <no-execution-found/>
        <artifact extension="jar" baseVersion="0.5-SNAPSHOT" groupId="com.example" artifactId="my-jar" id="com.example:my-jar:jar:0.5-SNAPSHOT" type="jar" version="0.5-20180211.151825-18" snapshot="true">
          <file>/path/to/my-project-workspace/target/my-jar-0.5-SNAPSHOT.jar</file>
        </artifact>
        <attachedArtifacts/>
    </ExecutionEvent>
     */

    /**
     *
     * @param artifactDeployedEvents list of "RepositoryEvent" of type "ARTIFACT_DEPLOYED"
     * @param filePath file path of the artifact we search for
     * @return The "RepositoryEvent" of type "ARTIFACT_DEPLOYED" or {@code null} if non found
     */
    @Nullable
    public static Element getArtifactDeployedEvent(
            @NonNull List<Element> artifactDeployedEvents, @NonNull String filePath) {
        for (Element artifactDeployedEvent : artifactDeployedEvents) {
            if (!"RepositoryEvent".equals(artifactDeployedEvent.getNodeName())
                    || !"ARTIFACT_DEPLOYED".equals(artifactDeployedEvent.getAttribute("type"))) {
                // skip unexpected element
                continue;
            }
            String deployedArtifactFilePath =
                    getUniqueChildElement(artifactDeployedEvent, "artifact").getAttribute("file");
            if (Objects.equals(filePath, deployedArtifactFilePath)) {
                return artifactDeployedEvent;
            }
        }
        return null;
    }

    /*
    <ExecutionEvent type="MojoSucceeded" class="org.apache.maven.lifecycle.internal.DefaultExecutionEvent" _time="2017-02-02 23:03:17.06">
       <project artifactIdId="supplychain-portal" groupId="com.acmewidgets.supplychain" name="supplychain-portal" version="0.0.7" />
       <plugin executionId="default-test" goal="test" groupId="org.apache.maven.plugins" artifactId="maven-surefire-plugin" version="2.18.1">
          <reportsDirectory>${project.build.directory}/surefire-reports</reportsDirectory>
       </plugin>
    </ExecutionEvent>
      */
    @NonNull
    public static List<Element> getExecutionEventsByPlugin(
            @NonNull Element mavenSpyLogs,
            String pluginGroupId,
            String pluginArtifactId,
            String pluginGoal,
            String... eventType) {
        Set<String> eventTypes = new HashSet<>(Arrays.asList(eventType));

        List<Element> result = new ArrayList<>();
        for (Element executionEventElt : getChildrenElements(mavenSpyLogs, "ExecutionEvent")) {

            if (eventTypes.contains(executionEventElt.getAttribute("type"))) {
                Element pluginElt = XmlUtils.getUniqueChildElementOrNull(executionEventElt, "plugin");
                if (pluginElt == null) {

                } else {
                    if (pluginElt.getAttribute("groupId").equals(pluginGroupId)
                            && pluginElt.getAttribute("artifactId").equals(pluginArtifactId)
                            && pluginElt.getAttribute("goal").equals(pluginGoal)) {
                        result.add(executionEventElt);
                    } else {

                    }
                }
            }
        }
        return result;
    }

    /*
      <ExecutionEvent type="MojoSucceeded" class="org.apache.maven.lifecycle.internal.DefaultExecutionEvent" _time="2017-09-26 23:55:44.188">
      <project baseDir="/path/to/my-project-workspace" file="/path/to/my-project-workspace/pom.xml" groupId="com.example" name="my-jar" artifactId="my-jar" version="0.3-SNAPSHOT">
        <build sourceDirectory="/path/to/my-project-workspace/src/main/java" directory="/path/to/my-project-workspace/target"/>
      </project>
      <plugin executionId="default-jar" goal="jar" lifecyclePhase="package" groupId="org.apache.maven.plugins" artifactId="maven-jar-plugin" version="2.4">
        <finalName>${jar.finalName}</finalName>
        <outputDirectory>${project.build.directory}</outputDirectory>
      </plugin>
    </ExecutionEvent>
       */
    @NonNull
    public static List<String> getExecutedLifecyclePhases(@NonNull Element mavenSpyLogs) {
        List<String> lifecyclePhases = new ArrayList<>();
        for (Element mojoSucceededEvent : getExecutionEvents(mavenSpyLogs, "MojoSucceeded")) {
            Element pluginElement = getUniqueChildElement(mojoSucceededEvent, "plugin");
            String lifecyclePhase = pluginElement.getAttribute("lifecyclePhase");
            if (!lifecyclePhases.contains(lifecyclePhase)) {
                lifecyclePhases.add(lifecyclePhase);
            }
        }

        return lifecyclePhases;
    }

    @Nullable
    public static String resolveMavenPlaceholders(Element targetElt, Element projectElt) {
        return resolveMavenPlaceholders(targetElt.getTextContent().trim(), projectElt);
    }

    @Nullable
    public static String resolveMavenPlaceholders(String target, Element projectElt) {
        String result = target;
        char separator = FileUtils.isWindows(result) ? '\\' : '/';
        if (result.contains("${project.build.directory}")) {
            String projectBuildDirectory = XmlUtils.getProjectBuildDirectory(projectElt);
            if (projectBuildDirectory == null || projectBuildDirectory.isEmpty()) {
                return null;
            }

            result = result.replace("${project.build.directory}", projectBuildDirectory);
        } else if (result.contains("${project.reporting.outputDirectory}")) {
            String projectBuildDirectory = XmlUtils.getProjectBuildDirectory(projectElt);
            if (projectBuildDirectory == null || projectBuildDirectory.isEmpty()) {
                return null;
            }
            result = result.replace("${project.reporting.outputDirectory}", projectBuildDirectory + separator + "site");
        } else if (result.contains("${basedir}")) {
            String baseDir = projectElt.getAttribute("baseDir");
            if (baseDir.isEmpty()) {
                return null;
            }

            result = result.replace("${basedir}", baseDir);
        } else if (!FileUtils.isAbsolutePath(result)) {
            String baseDir = projectElt.getAttribute("baseDir");
            if (baseDir.isEmpty()) {
                return null;
            }
            result = baseDir + separator + result;
        }
        return result;
    }

    /**
     * Relativize path
     * <p>
     * TODO replace all the workarounds (JENKINS-44088, JENKINS-46084, mac special folders...) by a unique call to
     * {@link File#getCanonicalPath()} on the workspace for the whole "MavenSpyLogProcessor#processMavenSpyLogs" code block.
     * We donb't want to pay an RPC call to {@link File#getCanonicalPath()} each time.
     *
     * @return relativized path
     * @throws IllegalArgumentException if {@code other} is not a {@code Path} that can be relativized
     *                                  against this path
     * @see java.nio.file.Path#relativize(Path)
     */
    @NonNull
    public static String getPathInWorkspace(@NonNull final String absoluteFilePath, @NonNull FilePath workspace) {
        boolean windows = FileUtils.isWindows(workspace);

        final String workspaceRemote = workspace.getRemote();

        // sanitize to workaround JENKINS-44088
        String sanitizedWorkspaceRemote = windows ? workspaceRemote.replace('\\', '/') : workspaceRemote;
        String sanitizedAbsoluteFilePath = windows ? absoluteFilePath.replace('\\', '/') : absoluteFilePath;

        if (workspaceRemote.startsWith("/var/") && absoluteFilePath.startsWith("/private/var/")) {
            // workaround MacOSX special folders path
            // eg String workspace =
            // "/var/folders/lq/50t8n2nx7l316pwm8gc_2rt40000gn/T/jenkinsTests.tmp/jenkins3845105900446934883test/workspace/build-on-master-with-tool-provided-maven";
            // eg String absolutePath =
            // "/private/var/folders/lq/50t8n2nx7l316pwm8gc_2rt40000gn/T/jenkinsTests.tmp/jenkins3845105900446934883test/workspace/build-on-master-with-tool-provided-maven/pom.xml";
            sanitizedWorkspaceRemote = workspaceRemote;
            sanitizedAbsoluteFilePath = absoluteFilePath.substring("/private".length());
        }

        if (StringUtils.startsWithIgnoreCase(sanitizedAbsoluteFilePath, sanitizedWorkspaceRemote)) {
            // OK
        } else if (sanitizedWorkspaceRemote.contains("/workspace/")
                && sanitizedAbsoluteFilePath.contains("/workspace/")) {
            // workaround JENKINS-46084
            // sanitizedAbsoluteFilePath = '/app/Jenkins/home/workspace/testjob/pom.xml'
            // sanitizedWorkspaceRemote = '/var/lib/jenkins/workspace/testjob'
            sanitizedAbsoluteFilePath =
                    "/workspace/" + StringUtils.substringAfter(sanitizedAbsoluteFilePath, "/workspace/");
            sanitizedWorkspaceRemote =
                    "/workspace/" + StringUtils.substringAfter(sanitizedWorkspaceRemote, "/workspace/");
        } else if (sanitizedWorkspaceRemote.endsWith("/workspace")
                && sanitizedAbsoluteFilePath.contains("/workspace/")) {
            // workspace = "/var/lib/jenkins/jobs/Test-Pipeline/workspace";
            // absolutePath = "/storage/jenkins/jobs/Test-Pipeline/workspace/pom.xml";
            sanitizedAbsoluteFilePath =
                    "workspace/" + StringUtils.substringAfter(sanitizedAbsoluteFilePath, "/workspace/");
            sanitizedWorkspaceRemote = "workspace/";
        } else {
            throw new IllegalArgumentException(
                    "Cannot relativize '" + absoluteFilePath + "' relatively to '" + workspace.getRemote() + "'");
        }

        String relativePath = StringUtils.removeStartIgnoreCase(sanitizedAbsoluteFilePath, sanitizedWorkspaceRemote);
        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }
        if (windows) {
            relativePath = relativePath.replace('/', '\\');
        }
        LOGGER.log(Level.FINEST, "getPathInWorkspace({0}, {1}: {2}", new Object[] {
            absoluteFilePath, workspaceRemote, relativePath
        });
        return relativePath;
    }

    /**
     * @deprecated  use {@link FileUtils#isWindows(FilePath)}
     */
    @Deprecated
    public static boolean isWindows(@NonNull FilePath path) {
        return FileUtils.isWindows(path);
    }

    /**
     * Return the File separator "/" or "\" that is effective on the remote agent.
     *
     * @param filePath
     * @return "/" or "\"
     */
    @NonNull
    public static String getFileSeparatorOnRemote(@NonNull FilePath filePath) {
        int indexOfSlash = filePath.getRemote().indexOf('/');
        int indexOfBackSlash = filePath.getRemote().indexOf('\\');
        if (indexOfSlash == -1) {
            return "\\";
        } else if (indexOfBackSlash == -1) {
            return "/";
        } else if (indexOfSlash < indexOfBackSlash) {
            return "/";
        } else {
            return "\\";
        }
    }

    /**
     * @param projectElt
     * @return {@code project/build/@directory"}
     */
    @Nullable
    public static String getProjectBuildDirectory(@NonNull Element projectElt) {
        Element build = XmlUtils.getUniqueChildElementOrNull(projectElt, "build");
        if (build == null) {
            return null;
        }
        return build.getAttribute("directory");
    }

    /**
     * Concatenate the given {@code elements} using the given {@code delimiter} to concatenate.
     */
    @NonNull
    public static String join(@NonNull Iterable<String> elements, @NonNull String delimiter) {
        StringBuilder result = new StringBuilder();
        Iterator<String> it = elements.iterator();
        while (it.hasNext()) {
            String element = it.next();
            result.append(element);
            if (it.hasNext()) {
                result.append(delimiter);
            }
        }
        return result.toString();
    }

    @NonNull
    public static List<MavenArtifact> listGeneratedArtifacts(Element mavenSpyLogs, boolean includeAttachedArtifacts) {

        List<Element> artifactDeployedEvents = XmlUtils.getArtifactDeployedEvents(mavenSpyLogs);

        List<MavenArtifact> result = new ArrayList<>();

        for (Element projectSucceededElt : XmlUtils.getExecutionEvents(mavenSpyLogs, "ProjectSucceeded")) {

            Element projectElt = XmlUtils.getUniqueChildElement(projectSucceededElt, "project");
            MavenArtifact projectArtifact = XmlUtils.newMavenArtifact(projectElt);
            MavenArtifact pomArtifact = new MavenArtifact();
            pomArtifact.setGroupId(projectArtifact.getGroupId());
            pomArtifact.setArtifactId(projectArtifact.getArtifactId());
            pomArtifact.setBaseVersion(projectArtifact.getBaseVersion());
            pomArtifact.setSnapshot(projectArtifact.isSnapshot());
            pomArtifact.setType("pom");
            pomArtifact.setExtension("pom");
            pomArtifact.setFile(projectElt.getAttribute("file"));
            Element artifactDeployedEvent =
                    XmlUtils.getArtifactDeployedEvent(artifactDeployedEvents, pomArtifact.getFile());
            if (artifactDeployedEvent == null) {
                // artifact has not been deployed ("mvn deploy")
                pomArtifact.setVersion(projectArtifact.getVersion());
            } else {
                pomArtifact.setVersion(XmlUtils.getUniqueChildElement(artifactDeployedEvent, "artifact")
                        .getAttribute("version"));
            }

            result.add(pomArtifact);

            Element artifactElt = XmlUtils.getUniqueChildElement(projectSucceededElt, "artifact");
            MavenArtifact mavenArtifact = XmlUtils.newMavenArtifact(artifactElt);
            if ("pom".equals(mavenArtifact.getType())) {
                // No file is generated in a "pom" type project, don't add the pom file itself
                // TODO: evaluate if we really want to skip this file - cyrille le clerc 2018-04-12
            } else {
                Element fileElt = XmlUtils.getUniqueChildElementOrNull(artifactElt, "file");
                if (fileElt == null
                        || fileElt.getTextContent() == null
                        || fileElt.getTextContent().isEmpty()) {
                    if (LOGGER.isLoggable(Level.FINER)) {
                        LOGGER.log(
                                Level.FINE,
                                "listGeneratedArtifacts: Project " + projectArtifact
                                        + ":  no associated file found for " + mavenArtifact + " in "
                                        + XmlUtils.toString(artifactElt));
                    }
                } else {
                    mavenArtifact.setFile(StringUtils.trim(fileElt.getTextContent()));

                    artifactDeployedEvent =
                            XmlUtils.getArtifactDeployedEvent(artifactDeployedEvents, mavenArtifact.getFile());
                    if (artifactDeployedEvent == null) {
                        // artifact has not been deployed ("mvn deploy")
                    } else {
                        mavenArtifact.setVersion(XmlUtils.getUniqueChildElement(artifactDeployedEvent, "artifact")
                                .getAttribute("version"));
                        mavenArtifact.setRepositoryUrl(
                                XmlUtils.getUniqueChildElement(artifactDeployedEvent, "repository")
                                        .getAttribute("url"));
                    }
                }
                result.add(mavenArtifact);
            }

            if (includeAttachedArtifacts) {
                Element attachedArtifactsParentElt =
                        XmlUtils.getUniqueChildElement(projectSucceededElt, "attachedArtifacts");
                List<Element> attachedArtifactsElts =
                        XmlUtils.getChildrenElements(attachedArtifactsParentElt, "artifact");
                for (Element attachedArtifactElt : attachedArtifactsElts) {
                    MavenArtifact attachedMavenArtifact = XmlUtils.newMavenArtifact(attachedArtifactElt);

                    Element fileElt = XmlUtils.getUniqueChildElementOrNull(attachedArtifactElt, "file");
                    if (fileElt == null
                            || fileElt.getTextContent() == null
                            || fileElt.getTextContent().isEmpty()) {
                        if (LOGGER.isLoggable(Level.FINER)) {
                            LOGGER.log(
                                    Level.FINER,
                                    "Project " + projectArtifact + ", no associated file found for attached artifact "
                                            + attachedMavenArtifact + " in " + XmlUtils.toString(attachedArtifactElt));
                        }
                    } else {
                        attachedMavenArtifact.setFile(StringUtils.trim(fileElt.getTextContent()));

                        Element attachedArtifactDeployedEvent = XmlUtils.getArtifactDeployedEvent(
                                artifactDeployedEvents, attachedMavenArtifact.getFile());
                        if (attachedArtifactDeployedEvent == null) {
                            // artifact has not been deployed ("mvn deploy")
                        } else {
                            attachedMavenArtifact.setVersion(
                                    XmlUtils.getUniqueChildElement(attachedArtifactDeployedEvent, "artifact")
                                            .getAttribute("version"));
                            attachedMavenArtifact.setRepositoryUrl(
                                    XmlUtils.getUniqueChildElement(attachedArtifactDeployedEvent, "repository")
                                            .getAttribute("url"));
                        }
                    }
                    result.add(attachedMavenArtifact);
                }
            }
        }

        return result;
    }

    /**
     * Copy {@link jenkins.util.xml.RestrictiveEntityResolver} as it is secured by {@link org.kohsuke.accmod.restrictions.NoExternalUse}.
     *
     * @see jenkins.util.xml.RestrictiveEntityResolver
     */
    public static final class RestrictiveEntityResolver implements EntityResolver {

        public static final RestrictiveEntityResolver INSTANCE = new RestrictiveEntityResolver();

        private RestrictiveEntityResolver() {
            // prevent multiple instantiation.
            super();
        }

        /**
         * Throws a SAXException if this tried to resolve any entity.
         */
        @Override
        public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
            throw new SAXException(
                    "Refusing to resolve entity with publicId(" + publicId + ") and systemId (" + systemId + ")");
        }
    }
}
