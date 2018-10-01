/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.feature.maven;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONReader;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public abstract class ProjectHelper {

    /** Read feature. */
    private static final String RAW_FEATURE_JSON = Feature.class.getName() + "/rawmain.json";
    private static final String RAW_TEST_FEATURE_JSON = Feature.class.getName() + "/rawtest.json";

    /** Assembled feature. */
    private static final String ASSEMBLED_FEATURE_JSON = Feature.class.getName() + "/assembledmain.json";
    private static final String ASSEMBLED_TEST_FEATURE_JSON = Feature.class.getName() + "/assembledtest.json";

    private static void store(final MavenProject project, final String key, final Map<String, Feature> features) {
        if ( features != null && !features.isEmpty()) {
            project.setContextValue(key, features.size());
            // we have to serialize as the dependency lifecycle participant uses a different class loader (!)
            int index = 0;
            for(final Map.Entry<String, Feature> entry : features.entrySet()) {
                try ( final StringWriter w1 = new StringWriter() ) {
                    FeatureJSONWriter.write(w1, entry.getValue());
                    project.setContextValue(key + "_" + String.valueOf(index), w1.toString());
                    project.setContextValue(key + "_" + String.valueOf(index) + "f", entry.getKey());
                    index++;
                } catch ( final IOException ioe) {
                    throw new RuntimeException(ioe.getMessage(), ioe);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Feature> getFeatures(final MavenProject project, final String key) {
        final String cacheKey = key + "-cache";
        Map<String, Feature> result = null;
        try {
            result = (Map<String, Feature>) project.getContextValue(cacheKey);
            if (!result.isEmpty() ) {
                final Feature f = result.values().iterator().next();
                f.getId();
            }
        } catch ( final Exception e) {
            // if we get a class cast exception, we read again
            result = null;
        }
        if ( result == null ) {
            final Integer size = (Integer)project.getContextValue(key);
            if ( size != null ) {
                result = new TreeMap<>();
                for(int i=0; i<size;i++) {
                    final String text = (String)project.getContextValue(key + "_" + String.valueOf(i));
                    if ( text == null ) {
                        throw new RuntimeException("Unable to get feature from internal store.");
                    }
                    final String file = (String)project.getContextValue(key + "_" + String.valueOf(i) + "f");
                    if ( file == null ) {
                        throw new RuntimeException("Unable to get feature from internal store.");
                    }
                    try ( final StringReader r = new StringReader(text) ) {
                        final Feature feature = FeatureJSONReader.read(r, project.getId());
                        result.put(file, feature);
                    } catch ( final IOException ioe) {
                        throw new RuntimeException(ioe.getMessage(), ioe);
                    }
                }
                project.setContextValue(cacheKey, result);
            }
        }
        return result != null ? result : Collections.emptyMap();
    }

    /**
     * Store all relevant information about the project for plugins to be
     * retrieved
     * @param info The project info
     */
    public static void storeProjectInfo(final FeatureProjectInfo info) {
        store(info.project, RAW_FEATURE_JSON, info.features);
        store(info.project, RAW_TEST_FEATURE_JSON, info.testFeatures);
        store(info.project, ASSEMBLED_FEATURE_JSON, info.assembledFeatures);
        store(info.project, ASSEMBLED_TEST_FEATURE_JSON, info.assembledTestFeatures);
    }

    /**
     * Get the assembled features from the project
     * @param project The maven projet
     * @return The assembled features or {@code null}
     */
    public static Map<String, Feature> getAssembledFeatures(final MavenProject project) {
        return getFeatures(project, ASSEMBLED_FEATURE_JSON);
    }

    /**
     * Get the raw feature from the project
     * @param project The maven projet
     * @return The raw features or {@code null}
     */
    public static Map<String, Feature> getFeatures(final MavenProject project) {
        return getFeatures(project, RAW_FEATURE_JSON);
    }

    /**
     * Get the assembled test feature from the project
     * @param project The maven projet
     * @return The assembled features or {@code null}
     */
    public static Map<String, Feature> getAssembledTestFeatures(final MavenProject project) {
        return getFeatures(project, ASSEMBLED_TEST_FEATURE_JSON);
    }

    /**
     * Get the raw test feature from the project
     * @param project The maven projet
     * @return The raw features or {@code null}
     */
    public static Map<String, Feature> getTestFeatures(final MavenProject project) {
        return getFeatures(project, RAW_TEST_FEATURE_JSON);
    }

    /**
     * Gets a configuration value for a plugin if it is set in the configuration for
     * the plugin or any configuration for an execution of the plugin.
     * @param plugin Plugin
     * @param name Configuration parameter.
     * @param defaultValue The default value if no configuration is found.
     * @return The default value if nothing is configured, the value otherwise.
     * @throws RuntimeException If more than one value is configured
     */
    public static String getConfigValue(final Plugin plugin,
            final String name,
            final String defaultValue) {
        final Set<String> values = new HashSet<>();
        final Xpp3Dom config = plugin == null ? null : (Xpp3Dom)plugin.getConfiguration();
        final Xpp3Dom globalNode = (config == null ? null : config.getChild(name));
        if ( globalNode != null ) {
            values.add(globalNode.getValue());
        }
        for(final PluginExecution exec : plugin.getExecutions()) {
            final Xpp3Dom cfg = (Xpp3Dom)exec.getConfiguration();
            final Xpp3Dom pluginNode = (cfg == null ? null : cfg.getChild(name));
            if ( pluginNode != null ) {
                values.add(pluginNode.getValue());
            }
        }
        if ( values.size() > 1 ) {
            throw new RuntimeException("More than one value configured in plugin (executions) of "
                    + plugin.getKey() + " for " + name + " : " + values);
        }
        return values.isEmpty() ? defaultValue : values.iterator().next();
    }

    /**
     * Get a resolved Artifact from the coordinates provided
     *
     * @return the artifact, which has been resolved.
     */
    public static Artifact getOrResolveArtifact(final MavenProject project,
            final MavenSession session,
            final ArtifactHandlerManager artifactHandlerManager,
            final ArtifactResolver resolver,
            final ArtifactId id) {
        final Set<Artifact> artifacts = project.getDependencyArtifacts();
        for(final Artifact artifact : artifacts) {
            if ( artifact.getGroupId().equals(id.getGroupId())
               && artifact.getArtifactId().equals(id.getArtifactId())
               && artifact.getVersion().equals(id.getVersion())
               && artifact.getType().equals(id.getVersion())
               && ((id.getClassifier() == null && artifact.getClassifier() == null) || (id.getClassifier() != null && id.getClassifier().equals(artifact.getClassifier()))) ) {
                return artifact;
            }
        }
        final Artifact prjArtifact = new DefaultArtifact(id.getGroupId(),
                id.getArtifactId(),
                VersionRange.createFromVersion(id.getVersion()),
                Artifact.SCOPE_PROVIDED,
                id.getType(),
                id.getClassifier(),
                artifactHandlerManager.getArtifactHandler(id.getType()));
        try {
            resolver.resolve(prjArtifact, project.getRemoteArtifactRepositories(), session.getLocalRepository());
        } catch (final ArtifactResolutionException | ArtifactNotFoundException e) {
            throw new RuntimeException("Unable to get artifact for " + id.toMvnId(), e);
        }
        return prjArtifact;
    }

    public static String toString(final Dependency d) {
        return "Dependency {groupId=" + d.getGroupId() + ", artifactId=" + d.getArtifactId() + ", version=" + d.getVersion() +
                (d.getClassifier() != null ? ", classifier=" + d.getClassifier() : "") +
                ", type=" + d.getType() + "}";
    }

    public static Dependency toDependency(final ArtifactId id, final String scope) {
        final Dependency dep = new Dependency();
        dep.setGroupId(id.getGroupId());
        dep.setArtifactId(id.getArtifactId());
        dep.setVersion(id.getVersion());
        dep.setType(id.getType());
        dep.setClassifier(id.getClassifier());
        dep.setScope(scope);

        return dep;
    }
}
