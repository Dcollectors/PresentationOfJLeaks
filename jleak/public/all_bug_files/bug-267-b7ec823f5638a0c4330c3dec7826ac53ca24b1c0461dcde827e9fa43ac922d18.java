/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.runtime.artifact;

import co.cask.cdap.api.artifact.ArtifactClasses;
import co.cask.cdap.api.artifact.ArtifactDescriptor;
import co.cask.cdap.api.templates.plugins.PluginClass;
import co.cask.cdap.common.ArtifactAlreadyExistsException;
import co.cask.cdap.common.ArtifactNotFoundException;
import co.cask.cdap.common.ArtifactRangeNotFoundException;
import co.cask.cdap.common.InvalidArtifactException;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.io.Locations;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.artifact.ArtifactRange;
import co.cask.cdap.proto.artifact.ArtifactSummary;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.inject.Inject;
import org.apache.twill.filesystem.Location;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import javax.annotation.Nullable;

/**
 * This class manages artifact and artifact metadata. It is mainly responsible for inspecting artifacts to determine
 * metadata for the artifact.
 */
public class ArtifactRepository {
  private final ArtifactStore artifactStore;
  private final ArtifactClassLoaderFactory artifactClassLoaderFactory;
  private final ArtifactInspector artifactInspector;

  @Inject
  ArtifactRepository(CConfiguration cConf, ArtifactStore artifactStore) {
    this.artifactStore = artifactStore;
    File baseUnpackDir = new File(cConf.get(Constants.CFG_LOCAL_DATA_DIR),
      cConf.get(Constants.AppFabric.TEMP_DIR)).getAbsoluteFile();
    this.artifactClassLoaderFactory = new ArtifactClassLoaderFactory(baseUnpackDir);
    this.artifactInspector = new ArtifactInspector(cConf, artifactClassLoaderFactory);
  }

  /**
   * Clear all artifacts in the given namespace. This method is only intended to be called by unit tests, and
   * when a namespace is being deleted.
   *
   * @param namespace the namespace to delete artifacts in.
   * @throws IOException if there was an error making changes in the meta store
   */
  public void clear(Id.Namespace namespace) throws IOException {
    artifactStore.clear(namespace);
  }

  /**
   * Get all artifacts in the given namespace, optionally including system artifacts as well. Will never return
   * null. If no artifacts exist, an empty list is returned. Namespace existence is not checked.
   *
   * @param namespace the namespace to get artifacts from
   * @param includeSystem whether system artifacts should be included in the results
   * @return an unmodifiable list of artifacts that belong to the given namespace
   * @throws IOException if there as an exception reading from the meta store
   */
  public List<ArtifactSummary> getArtifacts(Id.Namespace namespace, boolean includeSystem) throws IOException {
    List<ArtifactSummary> summaries = Lists.newArrayList();
    if (includeSystem) {
      convertAndAdd(summaries, artifactStore.getArtifacts(Id.Namespace.SYSTEM));
    }
    return Collections.unmodifiableList(convertAndAdd(summaries, artifactStore.getArtifacts(namespace)));
  }

  /**
   * Get all artifacts in the given namespace of the given name. Will never return null.
   * If no artifacts exist, an exception is thrown. Namespace existence is not checked.
   *
   * @param namespace the namespace to get artifacts from
   * @param name the name of artifacts to get
   * @return an unmodifiable list of artifacts in the given namespace of the given name
   * @throws IOException if there as an exception reading from the meta store
   * @throws ArtifactNotFoundException if no artifacts of the given name in the given namespace exist
   */
  public List<ArtifactSummary> getArtifacts(Id.Namespace namespace, String name)
    throws IOException, ArtifactNotFoundException {
    List<ArtifactSummary> summaries = Lists.newArrayList();
    return Collections.unmodifiableList(convertAndAdd(summaries, artifactStore.getArtifacts(namespace, name)));
  }

  /**
   * Get details about the given artifact. Will never return null.
   * If no such artifact exist, an exception is thrown. Namespace existence is not checked.
   *
   * @param artifactId the id of the artifact to get
   * @return details about the given artifact
   * @throws IOException if there as an exception reading from the meta store
   * @throws ArtifactNotFoundException if the given artifact does not exist
   */
  public ArtifactDetail getArtifact(Id.Artifact artifactId) throws IOException, ArtifactNotFoundException {
    return artifactStore.getArtifact(artifactId);
  }

  /**
   * Returns a {@link SortedMap} of plugin artifact to all plugins available for the given artifact. The keys
   * are sorted by the {@link ArtifactDescriptor} for the artifact that contains plugins available to the given
   * artifact.
   *
   * @param artifactId the id of the artifact to get plugins for
   * @return an unmodifiable sorted map from plugin artifact to plugins in that artifact
   * @throws IOException if there was an exception reading plugin metadata from the artifact store
   */
  public SortedMap<ArtifactDescriptor, List<PluginClass>> getPlugins(Id.Artifact artifactId) throws IOException {
    return artifactStore.getPluginClasses(artifactId);
  }

  /**
   * Returns a {@link SortedMap} of plugin artifact to all plugins of the given type available for the given artifact.
   * The keys are sorted by the {@link ArtifactDescriptor} for the artifact that contains plugins available to the given
   * artifact.
   *
   * @param artifactId the id of the artifact to get plugins for
   * @param pluginType the type of plugins to get
   * @return an unmodifiable sorted map from plugin artifact to plugins in that artifact
   * @throws IOException if there was an exception reading plugin metadata from the artifact store
   */
  public SortedMap<ArtifactDescriptor, List<PluginClass>> getPlugins(Id.Artifact artifactId,
                                                                     String pluginType) throws IOException {
    return artifactStore.getPluginClasses(artifactId, pluginType);
  }

  /**
   * Returns a {@link SortedMap} of plugin artifact to plugin available for the given artifact. The keys
   * are sorted by the {@link ArtifactDescriptor} for the artifact that contains plugins available to the given
   * artifact.
   *
   * @param artifactId the id of the artifact to get plugins for
   * @param pluginType the type of plugins to get
   * @param pluginName the name of plugins to get
   * @return an unmodifiable sorted map from plugin artifact to plugins in that artifact
   * @throws IOException if there was an exception reading plugin metadata from the artifact store
   */
  public SortedMap<ArtifactDescriptor, PluginClass> getPlugins(Id.Artifact artifactId, String pluginType,
                                                               String pluginName)
    throws IOException, PluginNotExistsException {
    return artifactStore.getPluginClasses(artifactId, pluginType, pluginName);
  }

  /**
   * Returns a {@link Map.Entry} representing the plugin information for the plugin being requested.
   *
   * @param artifactId the id of the artifact to get plugins for
   * @param pluginType plugin type name
   * @param pluginName plugin name
   * @param selector for selecting which plugin to use
   * @return the entry found or {@code null} if none was found
   * @throws IOException if there was an exception reading plugin metadata from the artifact store
   * @throws PluginNotExistsException if no plugins of the given type and name are available to the given artifact
   */
  public Map.Entry<ArtifactDescriptor, PluginClass> findPlugin(Id.Artifact artifactId, String pluginType,
                                                               String pluginName, PluginSelector selector)
    throws IOException, PluginNotExistsException {
    return selector.select(artifactStore.getPluginClasses(artifactId, pluginType, pluginName));
  }

  /**
   * Inspects and builds plugin and application information for the given artifact.
   *
   * @param artifactId the id of the artifact to inspect and store
   * @param artifactFile the artifact to inspect and store
   * @return detail about the newly added artifact
   * @throws IOException if there was an exception reading from the artifact store
   * @throws WriteConflictException if there was a write conflict writing to the ArtifactStore
   * @throws ArtifactAlreadyExistsException if the artifact already exists
   * @throws InvalidArtifactException if the artifact is invalid. For example, if it is not a zip file,
   *                                  or the application class given is not an Application.
   */
  public ArtifactDetail addArtifact(Id.Artifact artifactId, File artifactFile)
    throws IOException, WriteConflictException, ArtifactAlreadyExistsException, InvalidArtifactException {

    try (CloseableClassLoader parentClassLoader =
           artifactClassLoaderFactory.createClassLoader(Locations.toLocation(artifactFile))) {
      ArtifactClasses artifactClasses = artifactInspector.inspectArtifact(artifactId, artifactFile, parentClassLoader);
      ArtifactMeta meta = new ArtifactMeta(artifactClasses, ImmutableSet.<ArtifactRange>of());
      return artifactStore.write(artifactId, meta, Files.newInputStreamSupplier(artifactFile));
    }
  }

  /**
   * Inspects and builds plugin and application information for the given artifact.
   *
   * @param artifactId the id of the artifact to inspect and store
   * @param artifactFile the artifact to inspect and store
   * @param parentArtifacts artifacts the given artifact extends.
   *                        If null, the given artifact does not extend another artifact
   * @throws IOException if there was an exception reading from the artifact store
   * @throws ArtifactRangeNotFoundException if none of the parent artifacts could be found
   * @throws WriteConflictException if there was a write conflict writing to the ArtifactStore
   * @throws ArtifactAlreadyExistsException if the artifact already exists
   * @throws InvalidArtifactException if the artifact is invalid. For example, if it is not a zip file,
   *                                  or the application class given is not an Application.
   */
  public ArtifactDetail addArtifact(Id.Artifact artifactId, File artifactFile,
                                    @Nullable Set<ArtifactRange> parentArtifacts)
    throws IOException, ArtifactRangeNotFoundException, WriteConflictException,
    ArtifactAlreadyExistsException, InvalidArtifactException {

    CloseableClassLoader parentClassLoader;
    parentArtifacts = parentArtifacts == null ? ImmutableSet.<ArtifactRange>of() : parentArtifacts;
    if (parentArtifacts.isEmpty()) {
      // if this artifact doesn't extend another, use itself to create the parent classloader
      parentClassLoader = artifactClassLoaderFactory.createClassLoader(Locations.toLocation(artifactFile));
    } else {
      parentClassLoader = createParentClassLoader(artifactId, parentArtifacts);
    }

    try {
      ArtifactClasses artifactClasses = artifactInspector.inspectArtifact(artifactId, artifactFile, parentClassLoader);
      ArtifactMeta meta = new ArtifactMeta(artifactClasses, parentArtifacts);
      return artifactStore.write(artifactId, meta, Files.newInputStreamSupplier(artifactFile));
    } finally {
      parentClassLoader.close();
    }
  }

  // convert details to summaries (to hide location and other unnecessary information)
  private List<ArtifactSummary> convertAndAdd(List<ArtifactSummary> summaries, Iterable<ArtifactDetail> details) {
    for (ArtifactDetail detail : details) {
      ArtifactDescriptor descriptor = detail.getDescriptor();
      summaries.add(
        new ArtifactSummary(descriptor.getName(), descriptor.getVersion().getVersion(), descriptor.isSystem()));
    }
    return summaries;
  }

  /**
   * Create a parent classloader using an artifact from one of the artifacts in the specified parents.
   *
   * @param artifactId the id of the artifact to create the parent classloader for
   * @param parentArtifacts the ranges of parents to create the classloader from
   * @return a classloader based off a parent artifact
   * @throws ArtifactRangeNotFoundException if none of the parents could be found
   * @throws InvalidArtifactException if one of the parents also has parents
   * @throws IOException if there was some error reading from the store
   */
  private CloseableClassLoader createParentClassLoader(Id.Artifact artifactId, Set<ArtifactRange> parentArtifacts)
    throws ArtifactRangeNotFoundException, IOException, InvalidArtifactException {

    List<ArtifactDetail> parents = new ArrayList<>();
    for (ArtifactRange parentRange : parentArtifacts) {
      parents.addAll(artifactStore.getArtifacts(parentRange));
    }

    if (parents.isEmpty()) {
      throw new ArtifactRangeNotFoundException(parentArtifacts);
    }

    // check if any of the parents also have parents, which is not allowed. This is to simplify things
    // so that we don't have to chain a bunch of classloaders, and also to keep it simple for users to avoid
    // complicated dependency trees that are hard to manage.
    boolean isInvalid = false;
    StringBuilder errMsg = new StringBuilder("Invalid artifact '")
      .append(artifactId)
      .append("'.")
      .append(" Artifact parents cannot have parents.");
    for (ArtifactDetail parent : parents) {
      Set<ArtifactRange> grandparents = parent.getMeta().getUsableBy();
      if (!grandparents.isEmpty()) {
        isInvalid = true;
        errMsg
          .append(" Parent '")
          .append(parent.getDescriptor().getName())
          .append("-")
          .append(parent.getDescriptor().getVersion().getVersion())
          .append("' has parents.");
      }
    }
    if (isInvalid) {
      throw new InvalidArtifactException(errMsg.toString());
    }

    // assumes any of the parents will do
    Location parentLocation = parents.get(0).getDescriptor().getLocation();
    return artifactClassLoaderFactory.createClassLoader(parentLocation);
  }
}
