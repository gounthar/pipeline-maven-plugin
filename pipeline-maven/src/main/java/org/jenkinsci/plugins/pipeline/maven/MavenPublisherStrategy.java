package org.jenkinsci.plugins.pipeline.maven;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.DescriptorExtensionList;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.pipeline.maven.publishers.JacocoReportPublisher;

/**
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
public enum MavenPublisherStrategy {
    IMPLICIT(Messages.publisher_strategy_implicit_description()) {
        /**
         * <p>Build the list of {@link MavenPublisher}s that should be invoked for the build execution of the given {@link TaskListener}
         * with the desired configuration.
         * </p>
         * <p>
         * The desired configuration is based on:
         * </p>
         * <ul>
         * <li>The default configuration of the publishers</li>
         * <li>The global configuration of the publishers defined in the "Global Tools Configuration' section</li>
         * <li>The configuration specified in the {@code withMaven(options=[...])} step</li>
         * </ul>
         *  @param configuredPublishers
         * @param listener
         */
        @NonNull
        public List<MavenPublisher> buildPublishersList(
                @NonNull List<MavenPublisher> configuredPublishers, @NonNull TaskListener listener) {

            // configuration passed as parameter of "withMaven(options=[...]){}"
            // mavenPublisher.descriptor.id -> mavenPublisher
            Map<String, MavenPublisher> configuredPublishersById = new HashMap<>();
            for (MavenPublisher mavenPublisher : configuredPublishers) {
                if (mavenPublisher == null) {
                    // skip null publisher options injected by Jenkins pipeline, probably caused by a missing plugin
                } else {
                    configuredPublishersById.put(mavenPublisher.getDescriptor().getId(), mavenPublisher);
                }
            }

            // configuration defined globally
            Map<String, MavenPublisher> globallyConfiguredPublishersById = new HashMap<>();
            GlobalPipelineMavenConfig globalPipelineMavenConfig = GlobalPipelineMavenConfig.get();

            List<MavenPublisher> globallyConfiguredPublishers = globalPipelineMavenConfig == null
                    ? Collections.emptyList()
                    : globalPipelineMavenConfig.getPublisherOptions();
            if (globallyConfiguredPublishers == null) {
                globallyConfiguredPublishers = Collections.emptyList();
            }
            for (MavenPublisher mavenPublisher : globallyConfiguredPublishers) {
                globallyConfiguredPublishersById.put(
                        mavenPublisher.getDescriptor().getId(), mavenPublisher);
            }

            // mavenPublisher.descriptor.id -> mavenPublisher
            Map<String, MavenPublisher> defaultPublishersById = new HashMap<>();
            DescriptorExtensionList<MavenPublisher, Descriptor<MavenPublisher>> descriptorList =
                    Jenkins.get().getDescriptorList(MavenPublisher.class);
            for (Descriptor<MavenPublisher> descriptor : descriptorList) {
                try {
                    if (JacocoReportPublisher.DescriptorImpl.class.isAssignableFrom(descriptor.getClass())) {
                        listener.getLogger()
                                .println(
                                        """
                        [withMaven] JacocoPublisher is no longer implicitly used with `withMaven` step.
                        [withMaven] Jacoco plugin has been deprecated and should not be used anymore.
                        [withMaven] CoveragePublisher will be used instead.
                        """);
                    } else {
                        defaultPublishersById.put(descriptor.getId(), descriptor.clazz.newInstance());
                    }
                } catch (InstantiationException | IllegalAccessException e) {
                    PrintWriter error =
                            listener.error("[withMaven] Exception instantiation default config for Maven Publisher '"
                                    + descriptor.getDisplayName() + "' / " + descriptor.getId() + ": " + e);
                    e.printStackTrace(error);
                    error.close();
                    LOGGER.log(Level.WARNING, "Exception instantiating " + descriptor.clazz + ": " + e, e);
                    e.printStackTrace();
                }
            }

            if (LOGGER.isLoggable(Level.FINE)) {
                listener.getLogger()
                        .println("[withMaven] Maven Publishers with configuration provided by the pipeline: "
                                + configuredPublishersById.values());
                listener.getLogger()
                        .println("[withMaven] Maven Publishers with configuration defined globally: "
                                + globallyConfiguredPublishersById.values());
                listener.getLogger()
                        .println("[withMaven] Maven Publishers with default configuration: "
                                + defaultPublishersById.values());
            }

            // TODO FILTER
            List<MavenPublisher> results = new ArrayList<>();
            for (Map.Entry<String, MavenPublisher> entry : defaultPublishersById.entrySet()) {
                String publisherId = entry.getKey();
                MavenPublisher publisher = buildConfiguredMavenPublisher(
                        configuredPublishersById.get(publisherId),
                        globallyConfiguredPublishersById.get(publisherId),
                        entry.getValue(),
                        listener);
                results.add(publisher);
            }
            Collections.sort(results);
            return results;
        }
    },

    EXPLICIT(Messages.publisher_strategy_explicit_description()) {
        @NonNull
        @Override
        public List<MavenPublisher> buildPublishersList(
                @NonNull List<MavenPublisher> configuredPublishers, @NonNull TaskListener listener) {

            // filter null entries caused by missing plugins
            List<MavenPublisher> result = new ArrayList<>();
            for (MavenPublisher publisher : configuredPublishers) {
                if (publisher != null) {
                    result.add(publisher);
                }
            }

            if (LOGGER.isLoggable(Level.FINE)) {
                listener.getLogger().println("[withMaven] Maven Publishers: " + result);
            }
            return result;
        }
    };
    final String description;

    MavenPublisherStrategy(String description) {
        this.description = description;
    }

    public MavenPublisher buildConfiguredMavenPublisher(
            @Nullable MavenPublisher pipelinePublisher,
            @Nullable MavenPublisher globallyConfiguredPublisher,
            @NonNull MavenPublisher defaultPublisher,
            @NonNull TaskListener listener) {

        MavenPublisher result;
        String logMessage;

        if (pipelinePublisher == null && globallyConfiguredPublisher == null) {
            result = defaultPublisher;
            logMessage = "default";
        } else if (pipelinePublisher == null && globallyConfiguredPublisher != null) {
            result = globallyConfiguredPublisher;
            logMessage = "globally";
        } else if (pipelinePublisher != null && globallyConfiguredPublisher == null) {
            result = pipelinePublisher;
            logMessage = "pipeline";
        } else if (pipelinePublisher != null && globallyConfiguredPublisher != null) {
            // workaround FindBugs "Bug kind and pattern: NP - NP_NULL_ON_SOME_PATH"
            // check pipelinePublisher and globallyConfiguredPublisher are non null even if it is useless

            result = pipelinePublisher;
            logMessage = "pipeline";
            listener.getLogger()
                    .println(
                            "[withMaven] WARNING merging publisher configuration defined in the 'Global Tool Configuration' and at the pipeline level is not yet supported."
                                    + " Use pipeline level configuration for '"
                                    + result.getDescriptor().getDisplayName() + "'");
            //
            //            PropertyDescriptor[] propertyDescriptors =
            // PropertyUtils.getPropertyDescriptors(defaultPublisher);
            //            for(PropertyDescriptor propertyDescriptor: propertyDescriptors) {
            //                Method readMethod = propertyDescriptor.getReadMethod();
            //                Method writeMethod = propertyDescriptor.getWriteMethod();
            //
            //                Object defaultValue = readMethod.invoke(defaultPublisher);
            //                Object globallyDefinedValue = readMethod.invoke(globallyConfiguredPublisher);
            //                Object pipelineValue = readMethod.invoke(pipelinePublisher);
            //            }
        } else {
            throw new IllegalStateException("Should not happen, workaround for Findbugs NP_NULL_ON_SOME_PATH above");
        }

        if (LOGGER.isLoggable(Level.FINE))
            listener.getLogger()
                    .println("[withMaven] Use " + logMessage + " defined publisher for '"
                            + result.getDescriptor().getDisplayName() + "'");
        return result;
    }

    public String getDescription() {
        return description;
    }

    /**
     * <p>Build the list of {@link MavenPublisher}s that should be invoked for the build execution of the given {@link TaskListener}
     * with the desired configuration.
     * </p>
     * <p>
     * The desired configuration is based on:
     * </p>
     * <ul>
     * <li>The default configuration of the publishers</li>
     * <li>The global configuration of the publishers defined in the "Global Tools Configuration' section</li>
     * <li>The configuration specified in the {@code withMaven(options=[...])} step</li>
     * </ul>
     *  @param configuredPublishers
     * @param listener
     */
    @NonNull
    public abstract List<MavenPublisher> buildPublishersList(
            @NonNull List<MavenPublisher> configuredPublishers, @NonNull TaskListener listener);

    private static final Logger LOGGER = Logger.getLogger(MavenPublisherStrategy.class.getName());
}
