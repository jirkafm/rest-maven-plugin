
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.github.cjnygard.mvn.rest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status.Family;
import org.apache.commons.io.IOUtils;
import org.apache.maven.model.FileSet;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProjectHelper;
//import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.util.FileUtils;
import org.sonatype.plexus.build.incremental.BuildContext;
import org.codehaus.plexus.components.io.filemappers.FileMapper;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
//import org.codehaus.plexus.components.io.filemappers.AbstractFileMapper;
//import org.codehaus.plexus.components.io.filemappers.IdentityMapper;

/**
 * Make REST request, sending file contents and saving results to a file.
 *
 */
@Mojo( name = "rest-request" )
public class Plugin extends AbstractMojo
{

    public final class FileSetTransformer
    {

        private final FileSet fileSet;

        private FileSetTransformer( FileSet fileSet )
        {
            this.fileSet = fileSet;
        }

        public List<File> toFileList() throws MojoExecutionException
        {
            return toFileList( fileSet );
        }

        public List<File> toFileList( FileSet fs ) throws MojoExecutionException
        {
            try
            {
                File directory = new File( fs.getDirectory() );
                String includes = toString( fs.getIncludes() );
                String excludes = toString( fs.getExcludes() );
                return FileUtils.getFiles( directory, includes, excludes );
            }
            catch ( IOException e )
            {
                throw new MojoExecutionException( "Unable to get paths to files", e );
            }
        }

        private String toString( List<String> strings )
        {
            StringBuilder sb = new StringBuilder();
            for ( String string : strings )
            {
                if ( sb.length() > 0 )
                {
                    sb.append( ", " );
                }
                sb.append( string );
            }
            return sb.toString();
        }
    }

    public final class FileErrorInfo
    {

        private final String filename;
        private final int errorCode;
        private final String message;

        public FileErrorInfo( String fn, int code, String msg )
        {
            errorCode = code;
            filename = fn;
            message = msg;
        }

        public FileErrorInfo( String fn, String msg )
        {
            errorCode = -1;
            filename = fn;
            message = msg;
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append( filename ).append( " [" ).append( errorCode ).append( ":" ).append( message ).append( "]" );
            return sb.toString();
        }
    }

    @Parameter( defaultValue = "${session}", readonly = true )
    private MavenSession session;

    @Parameter( defaultValue = "${project}", readonly = true )
    private MavenProject project;

    @Parameter( defaultValue = "${mojoExecution}", readonly = true )
    private MojoExecution mojo;

    @Parameter( defaultValue = "${plugin}", readonly = true ) // Maven 3 only
    private PluginDescriptor plugin;

    @Parameter( defaultValue = "${settings}", readonly = true )
    private Settings settings;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Base directory for build.
     *
     * Default <code>${project.basedir}</code>
     *
     */
    @Parameter( defaultValue = "${project.basedir}", readonly = true )
    private File basedir;

    /**
     * Base directory for target.
     *
     * Default <code>${project.build.directory}</code>
     *
     */
    @Parameter( defaultValue = "${project.build.directory}", readonly = true )
    private File target;

    /**
     * A URL path to the base of the REST request resource.
     *
     */
    @Parameter( property = "endpoint" )
    private URI endpoint;

    /**
     * A resource path added to the endpoint URL to access the REST resource.
     *
     */
    @Parameter( property = "resource" )
    private String resource;

    /**
     * The method to use for the REST request.
     *
     * Defaults to <code>POST</code>
     *
     */
    @Parameter( property = "method" )
    private String method = "POST";

    /**
     * A list of <code>fileSet</code> rules to select files and directories.
     *
     */
    @Parameter( property = "filesets" )
    private List<FileSet> filesets = new ArrayList<FileSet>();

    /**
     * A {@link org.apache.maven.model.FileSet} rule to select files to send in the REST request.
     *
     */
    @Parameter( property = "fileset" )
    private FileSet fileset;

    /**
     * Path where REST query result files are stored.
     *
     * Defaults to <code>${project.build.directory}/rest</code>
     *
     */
    @Parameter( defaultValue = "${project.build.directory}/rest", property = "outputDir" )
    private File outputDir;

    /**
     * A <code>map</code> of query parameters to add to the REST request.
     *
     */
    @Parameter( property = "queryParams" )
    private Map<String, String> queryParams;

    /**
     * A <code>map</code> of query headers to add to the REST request.
     *
     */
    @Parameter( property = "headers" )
    private Map<String, String> headers;

    /**
     * A {@link org.codehaus.plexus.components.io.filemappers.FileMapper} object to generate output filenames.
     *
     * Provide a FileMapper to generate the output filename which is used to store the REST query results.
     */
    @Parameter( property = "filemapper" )
    private FileMapper fileMapper;

    /**
     * A list of <code>fileMapper</code> rules to generate output filenames.
     *
     */
    @Parameter( property = "filemappers" )
    private List<FileMapper> fileMappers;

    /**
     * The type of the data sent by the REST request.
     *
     * The data type of the REST request data. Default <code>MediaType.TEXT_PLAIN_TYPE</code>
     */
    @Parameter
    private MediaType requestType = MediaType.TEXT_PLAIN_TYPE;

    /**
     * The type of the data returned by the REST request.
     *
     * The expected data type of the REST response. Default <code>MediaType.APPLICATION_OCTET_STREAM_TYPE</code>
     */
    @Parameter
    private MediaType responseType = MediaType.APPLICATION_OCTET_STREAM_TYPE;

    /**
     * The Plexus BuildContext is used to identify files or directories modified since last build, implying
     * functionality used to define if java generation must be performed again.
     */
    @Component( role = org.sonatype.plexus.build.incremental.BuildContext.class )
    private BuildContext buildContext;

    /**
     * Note that the execution parameter will be injected ONLY if this plugin is executed as part of a maven standard
     * lifecycle - as opposed to directly invoked with a direct invocation. When firing this mojo directly (i.e.
     * {@code mvn xjc:something} or {@code mvn schemagen:something}), the {@code execution} object will not be injected.
     */
    @Parameter( defaultValue = "${mojoExecution}", readonly = true )
    private MojoExecution execution;

    private <T> T getInjectedObject( final T objectOrNull, final String objectName )
    {
        if ( objectOrNull == null )
        {
            getLog().error( String.format( "Found null [%s]: Maven @Component injection was not done properly.",
                                           objectName ) );
        }

        return objectOrNull;
    }

    /**
     * The Plexus BuildContext is used to identify files or directories modified since last build, implying
     * functionality used to define if java generation must be performed again.
     *
     * @return the active Plexus BuildContext.
     */
    protected final BuildContext getBuildContext()
    {
        return getInjectedObject( buildContext, "buildContext" );
    }

    /**
     * @return The active MavenProject.
     */
    protected final MavenProject getProject()
    {
        return getInjectedObject( project, "project" );
    }

    /**
     * @return The active MojoExecution.
     */
    public MojoExecution getExecution()
    {
        return getInjectedObject( execution, "execution" );
    }

    protected List<File> getFilesToProcess() throws MojoExecutionException
    {
        List<File> files = new ArrayList<>();
        if ( null != getFileset() )
        {
            if ( null == getFilesets() )
            {
                filesets = new ArrayList<>();
            }
            getFilesets().add( getFileset() );
        }
        if ( null != getFilesets() )
        {
            for ( FileSet fs : getFilesets() )
            {
                FileSetTransformer fileMgr = new FileSetTransformer( fs );
                files.addAll( fileMgr.toFileList() );
            }
        }
        return files;
    }

    protected String readStream( InputStream in ) throws MojoExecutionException
    {
        byte buf[] = new byte[1024];
        int sz = 0;
        StringBuilder result = new StringBuilder();
        try
        {
            while ( sz != -1 )
            {
                sz = in.read( buf );
                result.append( buf );
            }
            return result.toString();
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Unable to read result stream", e );
        }

    }

    protected <T> String wrap( String prefix, String suffix, List<T> tokens )
    {
        StringBuilder str = new StringBuilder();
        for ( T s : tokens )
        {
            str.append( prefix );
            str.append( s.toString() );
            str.append( suffix );
        }
        return str.toString();
    }

    protected <T> String join( String delim, List<T> tokens )
    {
        StringBuilder str = new StringBuilder();
        for ( T s : tokens )
        {
            str.append( s.toString() );
            str.append( delim );
        }
        return str.toString().substring( 0, -delim.length() );
    }

    public void pipeToFile( InputStream stream, File outputFile ) throws IOException
    {
        getLog().info( String.format( "Writing file [%s]", outputFile.getCanonicalPath() ) );
        OutputStream outStream = new FileOutputStream( outputFile );

        byte[] buffer = new byte[8 * 1024];
        int bytesRead;
        while ( (bytesRead = stream.read( buffer )) != -1 )
        {
            outStream.write( buffer, 0, bytesRead );
        }
        IOUtils.closeQuietly( stream );
        IOUtils.closeQuietly( outStream );
    }

    protected String remapFilename( String filename )
    {
        String remappedName = filename;
        if ( null != fileMapper )
        {
            return fileMapper.getMappedFileName( filename );
        }
        else
        { // iteratively modify the filename, apply all mappers in order
            if ( null != fileMappers )
            {
                for ( FileMapper fm : fileMappers )
                {
                    if ( null != fm )
                    {
                        remappedName = fm.getMappedFileName( remappedName );
                    }
                }
            }
        }

        return remappedName;
    }

    protected boolean validateOutputDir() throws MojoExecutionException
    {
        try
        {
            if ( null == getOutputDir() )
            {
                outputDir = new File( getProject().getBasedir(), "rest" );
            }

            if ( !outputDir.isDirectory() )
            {
                if ( outputDir.isFile() )
                {
                    getLog().error( String.format( "Error: [%s] is not a directory",
                                                   outputDir.getCanonicalPath() ) );
                }
                else
                {
                    if ( !outputDir.mkdirs() )
                    {
                        getLog().error( String.format( "Error: Unable to create path[%s]",
                                                       outputDir.getCanonicalPath() ) );

                    }
                }
            }
        }
        catch ( IOException ex )
        {
            getLog().error( String.format( "IOException: [%s]", ex.toString() ) );
            throw new MojoExecutionException( String.format( "Unable to create destination dir [%s]: [%s]",
                                                             outputDir.toString(), ex.toString() ) );
        }
        return true;
    }

    @Override
    public void execute() throws MojoExecutionException
    {
        validateOutputDir();
        getLog().info( String.format( "Output dir [%s]", getOutputDir().toString() ) );

        List<File> files = getFilesToProcess();
        if ( null == files || files.size() <= 0 )
        {
            getLog().info( "No files to process" );
            return;
        }

        Client client = ClientBuilder.newClient();

        WebTarget baseTarget = client.target( getEndpoint() );
        if ( null != getResource() )
        {
            getLog().debug( String.format( "Setting resource [%s]", getResource() ) );
            baseTarget = baseTarget.path( getResource() );
        }
        // Load up the query parameters if they exist
        if ( null != getQueryParams() )
        {
            for ( String k : getQueryParams().keySet() )
            {
                String param = getQueryParams().get( k );
                baseTarget = baseTarget.queryParam( k, param );
                getLog().debug( String.format( "Param [%s:%s]", k, param ) );
            }
        }

        Invocation.Builder builder = baseTarget
                           .request( getRequestType() )
                           .accept( getResponseType() );
        // load up the header info
        if ( null != getHeaders() )
        {
            for ( String k : getHeaders().keySet() )
            {
                String hdr = getHeaders().get( k );
                builder = builder.header( k, hdr );
                getLog().debug( String.format( "Header [%s:%s]", k, hdr ) );
            }
        }
        getLog().info( String.format( "Endpoint: [%s %s]", getMethod(), baseTarget.getUri() ) );

        List<FileErrorInfo> errorFiles = new ArrayList<>();
        for ( File f : files )
        {
            getLog().debug( String.format( "Submitting file [%s]", f.toString() ) );
            Response response = builder.method( getMethod(), Entity.entity( f, getRequestType() ) );

            if ( response.getStatusInfo().getFamily() == Family.SUCCESSFUL )
            {
                getLog().debug( String.format( "Status: [%d]", response.getStatus() ) );
                InputStream in = response.readEntity( InputStream.class );
                try
                {
                    String outputFilename = remapFilename( f.getName() );
                    File of = new File( getOutputDir(), outputFilename );
                    pipeToFile( in, of );
                }
                catch ( IOException ex )
                {
                    getLog().debug( String.format( "IOException: [%s]", ex.toString() ) );
                    errorFiles.add( new FileErrorInfo( f.getPath(),
                                                       String.format( "IOException: [%s]", ex.getMessage() ) ) );
                }

            }
            else
            {
                getLog().debug( String.format( "Error code: [%d]", response.getStatus() ) );
                getLog().debug( response.getEntity().toString() );
                errorFiles.add( new FileErrorInfo( f.getPath(), response.getStatus(),
                                                   response.getEntity().toString() ) );
            }
        }

        if ( errorFiles.size() > 0 )
        {
            throw new MojoExecutionException( String.format( "Unable to process files:\n%s",
                                                             wrap( "  ", "\n", errorFiles ) ) );
        }
    }

    /**
     * @return the endpoint
     */
    public URI getEndpoint()
    {
        return endpoint;
    }

    /**
     * @return the resource
     */
    public String getResource()
    {
        return resource;
    }

    /**
     * @return the filesets
     */
    public List<FileSet> getFilesets()
    {
        return filesets;
    }

    /**
     * @return the fileset
     */
    public FileSet getFileset()
    {
        return fileset;
    }

    /**
     * @return the outputDir
     */
    public File getOutputDir()
    {
        return outputDir;
    }

    /**
     * @return the requestType
     */
    public MediaType getRequestType()
    {
        return requestType;
    }

    /**
     * @return the responseType
     */
    public MediaType getResponseType()
    {
        return responseType;
    }

    /**
     * @return the queryParams
     */
    public Map<String, String> getQueryParams()
    {
        return queryParams;
    }

    /**
     * @return the headers
     */
    public Map<String, String> getHeaders()
    {
        return headers;
    }

    /**
     * @return the fileMapper
     */
    public FileMapper getFileMapper()
    {
        return fileMapper;
    }

    /**
     * @return the fileMappers
     */
    public List<FileMapper> getFileMappers()
    {
        return fileMappers;
    }

    /**
     * @return the basedir
     */
    public File getBasedir()
    {
        return basedir;
    }

    /**
     * @return the target
     */
    public File getTarget()
    {
        return target;
    }

    /**
     * @return the projectHelper
     */
    public MavenProjectHelper getProjectHelper()
    {
        return projectHelper;
    }

    /**
     * @return the method
     */
    public String getMethod()
    {
        return method;
    }

    /**
     * @param method the method to set
     */
    public void setMethod( String method )
    {
        this.method = method;
    }

}