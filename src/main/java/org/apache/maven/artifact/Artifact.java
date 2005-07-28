package org.apache.maven.artifact;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.versioning.VersionRange;

import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Description of an artifact.
 *
 * @todo do we really need an interface here?
 */
public interface Artifact
    extends Comparable
{
    String LATEST_VERSION = "LATEST";

    String SNAPSHOT_VERSION = "SNAPSHOT";

    Pattern VERSION_FILE_PATTERN = Pattern.compile( "^(.*)-([0-9]{8}.[0-9]{6})-([0-9]+)$" );

    // TODO: into scope handler
    String SCOPE_COMPILE = "compile";

    String SCOPE_TEST = "test";

    String SCOPE_RUNTIME = "runtime";

    String SCOPE_PROVIDED = "provided";

    String getGroupId();

    String getArtifactId();

    String getVersion();

    void setVersion( String version );

    /**
     * Get the scope of the artifact. If the artifact is a standalone rather than a dependency, it's scope will be
     * <code>null</code>. The scope may not be the same as it was declared on the original dependency, as this is the
     * result of combining it with the main project scope.
     *
     * @return the scope
     */
    String getScope();

    String getType();

    String getClassifier();

    // only providing this since classifier is *very* optional...
    boolean hasClassifier();

    File getFile();

    void setFile( File destination );

    String getBaseVersion();

    /**
     * @todo would like to get rid of this - or at least only have one. Base version should be immutable.
     */
    void setBaseVersion( String baseVersion );

    // ----------------------------------------------------------------------

    String getId();

    String getDependencyConflictId();

    void addMetadata( ArtifactMetadata metadata );

    List getMetadataList();

    void setRepository( ArtifactRepository remoteRepository );

    ArtifactRepository getRepository();

    void updateVersion( String version, ArtifactRepository localRepository );

    String getDownloadUrl();

    void setDownloadUrl( String downloadUrl );

    ArtifactFilter getDependencyFilter();

    void setDependencyFilter( ArtifactFilter artifactFilter );

    ArtifactHandler getArtifactHandler();

    List getDependencyTrail();

    void setDependencyTrail( List dependencyTrail );

    void setScope( String scope );

    VersionRange getVersionRange();

    void setVersionRange( VersionRange newRange );

    void selectVersion( String version );

    void setGroupId( String groupId );

    void setArtifactId( String artifactId );

    boolean isSnapshot();

}