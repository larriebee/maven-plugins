package org.apache.maven.plugin.war;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.war.overlay.OverlayManager;
import org.apache.maven.plugin.war.packaging.OverlayPackagingTask;
import org.apache.maven.plugin.war.packaging.SaveWebappStructurePostPackagingTask;
import org.apache.maven.plugin.war.packaging.WarPackagingContext;
import org.apache.maven.plugin.war.packaging.WarPackagingTask;
import org.apache.maven.plugin.war.packaging.WarPostPackagingTask;
import org.apache.maven.plugin.war.packaging.WarProjectPackagingTask;
import org.apache.maven.plugin.war.util.CompositeMap;
import org.apache.maven.plugin.war.util.PropertyUtils;
import org.apache.maven.plugin.war.util.ReflectionProperties;
import org.apache.maven.plugin.war.util.WebappStructure;
import org.apache.maven.plugin.war.util.WebappStructureSerializer;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public abstract class AbstractWarMojo
    extends AbstractMojo
{
    /**
     * The maven project.
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * The directory containing generated classes.
     *
     * @parameter expression="${project.build.outputDirectory}"
     * @required
     * @readonly
     */
    private File classesDirectory;

    /**
     * Whether a JAR file will be created for the classes in the webapp. Using this optional configuration
     * parameter will make the generated classes to be archived into a jar file
     * and the classes directory will then be excluded from the webapp.
     *
     * @parameter expression="${archiveClasses}" default-value="false"
     */
    private boolean archiveClasses;

    /**
     * The Jar archiver needed for archiving classes directory into jar file under WEB-INF/lib.
     *
     * @parameter expression="${component.org.codehaus.plexus.archiver.Archiver#jar}"
     * @required
     */
    private JarArchiver jarArchiver;

    /**
     * The directory where the webapp is built.
     *
     * @parameter expression="${project.build.directory}/${project.build.finalName}"
     * @required
     */
    private File webappDirectory;

    /**
     * Single directory for extra files to include in the WAR.
     *
     * @parameter expression="${basedir}/src/main/webapp"
     * @required
     */
    private File warSourceDirectory;

    /**
     * The list of webResources we want to transfer.
     *
     * @parameter
     */
    private Resource[] webResources;

    /**
     * Filters (property files) to include during the interpolation of the pom.xml.
     *
     * @parameter expression="${project.build.filters}"
     */
    private List filters;

    /**
     * The path to the web.xml file to use.
     *
     * @parameter expression="${maven.war.webxml}"
     */
    private File webXml;

    /**
     * The path to the context.xml file to use.
     *
     * @parameter expression="${maven.war.containerConfigXML}"
     */
    private File containerConfigXML;

    /**
     * Directory to unpack dependent WARs into if needed
     *
     * @parameter expression="${project.build.directory}/war/work"
     * @required
     */
    private File workDirectory;

    /**
     * The file name mapping to use to copy libraries and tlds. If no file mapping is
     * set (default) the file is copied with its standard name.
     *
     * @parameter
     * @since 2.0.3
     */
    private String outputFileNameMapping;

    /**
     * The file containing the webapp structure cache.
     *
     * @parameter expression="${project.build.directory}/war/work/webapp-cache.xml"
     * @required
     * @since 2.1
     */
    private File cacheFile;

    /**
     * Whether the cache should be used to save the status of the webapp
     * accross multiple runs.
     *
     * @parameter expression="${useCache}" default-value="true"
     * @since 2.1
     */
    private boolean useCache = true;

    /**
     * To look up Archiver/UnArchiver implementations
     *
     * @parameter expression="${component.org.codehaus.plexus.archiver.manager.ArchiverManager}"
     * @required
     */
    protected ArchiverManager archiverManager;

    private static final String WEB_INF = "WEB-INF";

    private static final String META_INF = "META-INF";

    public static final String DEFAULT_FILE_NAME_MAPPING_CLASSIFIER =
        "${artifactId}-${version}-${classifier}.${extension}";

    public static final String DEFAULT_FILE_NAME_MAPPING = "${artifactId}-${version}.${extension}";

    /**
     * The comma separated list of tokens to include in the WAR.
     * Default is '**'.
     *
     * @parameter alias="includes"
     */
    private String warSourceIncludes = "**";

    /**
     * The comma separated list of tokens to exclude from the WAR.
     *
     * @parameter alias="excludes"
     */
    private String warSourceExcludes;

    /**
     * The comma separated list of tokens to include when doing
     * a war overlay.
     * Default is '**'
     *
     * @parameter
     */
    private String dependentWarIncludes = "**/**";

    /**
     * The comma separated list of tokens to exclude when doing
     * a war overlay.
     *
     * @parameter
     */
    private String dependentWarExcludes = "META-INF/**";

    /**
     * The overlays to apply.
     *
     * @parameter
     * @since 2.1
     */
    private List overlays = new ArrayList();

    /**
     * The maven archive configuration to use.
     *
     * @parameter
     */
    protected MavenArchiveConfiguration archive = new MavenArchiveConfiguration();

    private static final String[] EMPTY_STRING_ARRAY = {};

    private final WebappStructureSerializer webappStructureSerialier = new WebappStructureSerializer();

    /**
     * Returns a string array of the excludes to be used
     * when assembling/copying the war.
     *
     * @return an array of tokens to exclude
     */
    protected String[] getExcludes()
    {
        List excludeList = new ArrayList();
        if ( StringUtils.isNotEmpty( warSourceExcludes ) )
        {
            excludeList.addAll( Arrays.asList( StringUtils.split( warSourceExcludes, "," ) ) );
        }

        // if webXML is specified, omit the one in the source directory
        if ( webXml != null && StringUtils.isNotEmpty( webXml.getName() ) )
        {
            excludeList.add( "**/" + WEB_INF + "/web.xml" );
        }

        // if contextXML is specified, omit the one in the source directory
        if ( containerConfigXML != null && StringUtils.isNotEmpty( containerConfigXML.getName() ) )
        {
            excludeList.add( "**/" + META_INF + "/" + containerConfigXML.getName() );
        }

        return (String[]) excludeList.toArray( EMPTY_STRING_ARRAY );
    }

    /**
     * Returns a string array of the includes to be used
     * when assembling/copying the war.
     *
     * @return an array of tokens to include
     */
    protected String[] getIncludes()
    {
        return StringUtils.split( StringUtils.defaultString( warSourceIncludes ), "," );
    }

    /**
     * Returns a string array of the excludes to be used
     * when adding dependent wars as an overlay onto this war.
     *
     * @return an array of tokens to exclude
     */
    protected String[] getDependentWarExcludes()
    {
        String[] excludes;
        if ( StringUtils.isNotEmpty( dependentWarExcludes ) )
        {
            excludes = StringUtils.split( dependentWarExcludes, "," );
        }
        else
        {
            excludes = EMPTY_STRING_ARRAY;
        }
        return excludes;
    }

    /**
     * Returns a string array of the includes to be used
     * when adding dependent wars as an overlay onto this war.
     *
     * @return an array of tokens to include
     */
    protected String[] getDependentWarIncludes()
    {
        return StringUtils.split( StringUtils.defaultString( dependentWarIncludes ), "," );
    }

    public void buildExplodedWebapp( File webappDirectory )
        throws MojoExecutionException, MojoFailureException
    {
        getLog().info( "Exploding webapp..." );

        webappDirectory.mkdirs();

        try
        {
            buildWebapp( project, webappDirectory );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Could not explode webapp...", e );
        }
    }


    /**
     * Builds the webapp for the specified project with the new packaging task
     * thingy
     * <p/>
     * Classes, libraries and tld files are copied to
     * the <tt>webappDirectory</tt> during this phase.
     *
     * @param project         the maven project
     * @param webappDirectory the target directory
     * @throws MojoExecutionException if an error occured while packaging the webapp
     * @throws MojoFailureException   if an unexpected error occured while packaging the webapp
     * @throws IOException            if an error occured while copying the files
     */
    public void buildWebapp( MavenProject project, File webappDirectory )
        throws MojoExecutionException, MojoFailureException, IOException
    {

        WebappStructure cache;
        if ( useCache && cacheFile.exists() )
        {
            cache = new WebappStructure( webappStructureSerialier.fromXml( cacheFile ) );
        }
        else
        {
            cache = new WebappStructure( null );
        }

        final long startTime = System.currentTimeMillis();
        getLog().info( "Assembling webapp[" + project.getArtifactId() + "] in [" + webappDirectory + "]" );

        final OverlayManager overlayManager =
            new OverlayManager( overlays, project, dependentWarIncludes, dependentWarExcludes );
        final List packagingTasks = getPackagingTasks( overlayManager );
        final WarPackagingContext context = new DefaultWarPackagingContext( webappDirectory, cache, overlayManager );
        final Iterator it = packagingTasks.iterator();
        while ( it.hasNext() )
        {
            WarPackagingTask warPackagingTask = (WarPackagingTask) it.next();
            warPackagingTask.performPackaging( context );
        }

        // Post packaging
        final List postPackagingTasks = getPostPackagingTasks();
        final Iterator it2 = postPackagingTasks.iterator();
        while ( it2.hasNext() )
        {
            WarPostPackagingTask task = (WarPostPackagingTask) it2.next();
            task.performPostPackaging( context );

        }
        getLog().info( "Webapp assembled in[" + ( System.currentTimeMillis() - startTime ) + " msecs]" );

    }

    /**
     * Returns a <tt>List</tt> of the {@link org.apache.maven.plugin.war.packaging.WarPackagingTask}
     * instances to invoke to perform the packaging.
     *
     * @param overlayManager the overlay manager
     * @return the list of packaging tasks
     * @throws MojoExecutionException if the packaging tasks could not be built
     */
    private List getPackagingTasks( OverlayManager overlayManager )
        throws MojoExecutionException
    {
        final List packagingTasks = new ArrayList();
        final List resolvedOverlays = overlayManager.getOverlays();
        final Iterator it = resolvedOverlays.iterator();
        while ( it.hasNext() )
        {
            Overlay overlay = (Overlay) it.next();
            if ( overlay.isCurrentProject() )
            {
                packagingTasks.add( new WarProjectPackagingTask( webResources, webXml, containerConfigXML ) );
            }
            else
            {
                packagingTasks.add( new OverlayPackagingTask( overlay ) );
            }
        }
        return packagingTasks;
    }


    /**
     * Returns a <tt>List</tt> of the {@link org.apache.maven.plugin.war.packaging.WarPostPackagingTask}
     * instances to invoke to perform the post-packaging.
     *
     * @return the list of post packaging tasks
     */
    private List getPostPackagingTasks()
    {
        final List postPackagingTasks = new ArrayList();
        if ( useCache )
        {
            postPackagingTasks.add( new SaveWebappStructurePostPackagingTask( cacheFile ) );
        }
        // TODO add lib scanning to detect duplicates
        return postPackagingTasks;
    }

    // War packaging implementation

    private class DefaultWarPackagingContext
        implements WarPackagingContext
    {


        private final WebappStructure webappStructure;

        private final File webappDirectory;

        private final OverlayManager overlayManager;

        public DefaultWarPackagingContext( File webappDirectory, final WebappStructure webappStructure,
                                           final OverlayManager overlayManager )
        {
            this.webappDirectory = webappDirectory;
            this.webappStructure = webappStructure;
            this.overlayManager = overlayManager;

            // This is kinda stupid but if we loop over the current overlays and we request the path structure
            // it will register it. This will avoid wrong warning messages in a later phase
            final Iterator it = overlayManager.getOverlayIds().iterator();
            while ( it.hasNext() )
            {
                String overlayId = (String) it.next();
                webappStructure.getStructure( overlayId );
            }
        }

        public MavenProject getProject()
        {
            return project;
        }

        public File getWebappDirectory()
        {
            return webappDirectory;
        }

        public File getClassesDirectory()
        {
            return classesDirectory;
        }

        public Log getLog()
        {
            return AbstractWarMojo.this.getLog();
        }

        public String getOutputFileNameMapping()
        {
            return outputFileNameMapping;
        }

        public File getWebappSourceDirectory()
        {
            return warSourceDirectory;
        }

        public String[] getWebappSourceIncludes()
        {
            return getIncludes();
        }

        public String[] getWebappSourceExcludes()
        {
            return getExcludes();
        }

        public boolean archiveClasses()
        {
            return archiveClasses;
        }

        public File getOverlaysWorkDirectory()
        {
            return workDirectory;
        }

        public ArchiverManager getArchiverManager()
        {
            return archiverManager;
        }

        public MavenArchiveConfiguration getArchive()
        {
            return archive;
        }

        public JarArchiver getJarArchiver()
        {
            return jarArchiver;
        }

        public List getFilters()
        {
            return filters;
        }

        public Map getFilterProperties()
            throws MojoExecutionException
        {
            Map filterProperties = new Properties();

            // System properties
            filterProperties.putAll( System.getProperties() );

            // Project properties
            filterProperties.putAll( project.getProperties() );

            for ( Iterator i = filters.iterator(); i.hasNext(); )
            {
                String filtersfile = (String) i.next();

                try
                {
                    Properties properties = PropertyUtils.loadPropertyFile( new File( filtersfile ), true, true );

                    filterProperties.putAll( properties );
                }
                catch ( IOException e )
                {
                    throw new MojoExecutionException( "Error loading property file '" + filtersfile + "'", e );
                }
            }

            // can't putAll, as ReflectionProperties doesn't enumerate - so we make a composite map with the project variables as dominant
            return new CompositeMap( new ReflectionProperties( project ), filterProperties );
        }

        public WebappStructure getWebappStructure()
        {
            return webappStructure;
        }

        public List getOwnerIds()
        {
            return overlayManager.getOverlayIds();
        }
    }

    public MavenProject getProject()
    {
        return project;
    }

    public void setProject( MavenProject project )
    {
        this.project = project;
    }

    public File getClassesDirectory()
    {
        return classesDirectory;
    }

    public void setClassesDirectory( File classesDirectory )
    {
        this.classesDirectory = classesDirectory;
    }

    public File getWebappDirectory()
    {
        return webappDirectory;
    }

    public void setWebappDirectory( File webappDirectory )
    {
        this.webappDirectory = webappDirectory;
    }

    public File getWarSourceDirectory()
    {
        return warSourceDirectory;
    }

    public void setWarSourceDirectory( File warSourceDirectory )
    {
        this.warSourceDirectory = warSourceDirectory;
    }

    public File getWebXml()
    {
        return webXml;
    }

    public void setWebXml( File webXml )
    {
        this.webXml = webXml;
    }

    public File getContainerConfigXML()
    {
        return containerConfigXML;
    }

    public void setContainerConfigXML( File containerConfigXML )
    {
        this.containerConfigXML = containerConfigXML;
    }

    public String getOutputFileNameMapping()
    {
        return outputFileNameMapping;
    }

    public void setOutputFileNameMapping( String outputFileNameMapping )
    {
        this.outputFileNameMapping = outputFileNameMapping;
    }

    public List getOverlays()
    {
        return overlays;
    }

    public void setOverlays( List overlays )
    {
        this.overlays = overlays;
    }

    public void addOverlay( Overlay overlay )
    {
        overlays.add( overlay );
    }

    public boolean isArchiveClasses()
    {
        return archiveClasses;
    }

    public void setArchiveClasses( boolean archiveClasses )
    {
        this.archiveClasses = archiveClasses;
    }

    public JarArchiver getJarArchiver()
    {
        return jarArchiver;
    }

    public void setJarArchiver( JarArchiver jarArchiver )
    {
        this.jarArchiver = jarArchiver;
    }

    public Resource[] getWebResources()
    {
        return webResources;
    }

    public void setWebResources( Resource[] webResources )
    {
        this.webResources = webResources;
    }

    public List getFilters()
    {
        return filters;
    }

    public void setFilters( List filters )
    {
        this.filters = filters;
    }

    public File getWorkDirectory()
    {
        return workDirectory;
    }

    public void setWorkDirectory( File workDirectory )
    {
        this.workDirectory = workDirectory;
    }

    public File getCacheFile()
    {
        return cacheFile;
    }

    public void setCacheFile( File cacheFile )
    {
        this.cacheFile = cacheFile;
    }

    public String getWarSourceIncludes()
    {
        return warSourceIncludes;
    }

    public void setWarSourceIncludes( String warSourceIncludes )
    {
        this.warSourceIncludes = warSourceIncludes;
    }

    public String getWarSourceExcludes()
    {
        return warSourceExcludes;
    }

    public void setWarSourceExcludes( String warSourceExcludes )
    {
        this.warSourceExcludes = warSourceExcludes;
    }


    public boolean isUseCache()
    {
        return useCache;
    }

    public void setUseCache( boolean useCache )
    {
        this.useCache = useCache;
    }
}
