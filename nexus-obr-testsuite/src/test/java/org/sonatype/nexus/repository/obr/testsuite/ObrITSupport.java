/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2012 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.obr.testsuite;

import static org.sonatype.nexus.testsuite.support.ParametersLoaders.firstAvailableTestParameters;
import static org.sonatype.nexus.testsuite.support.ParametersLoaders.systemTestParameters;
import static org.sonatype.nexus.testsuite.support.ParametersLoaders.testParameters;
import static org.sonatype.sisu.filetasks.builder.FileRef.file;
import static org.sonatype.sisu.goodies.common.Varargs.$;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.codehaus.plexus.util.FileUtils;
import org.junit.runners.Parameterized;
import org.sonatype.nexus.bundle.launcher.NexusBundleConfiguration;
import org.sonatype.nexus.client.core.subsystem.content.Content;
import org.sonatype.nexus.client.core.subsystem.content.Location;
import org.sonatype.nexus.client.core.subsystem.repository.Repositories;
import org.sonatype.nexus.testsuite.support.NexusRunningParametrizedITSupport;
import org.sonatype.nexus.testsuite.support.NexusStartAndStopStrategy;

@NexusStartAndStopStrategy( NexusStartAndStopStrategy.Strategy.EACH_TEST )
public abstract class ObrITSupport
    extends NexusRunningParametrizedITSupport
{

    @Parameterized.Parameters
    public static Collection<Object[]> data()
    {
        return firstAvailableTestParameters(
            systemTestParameters(),
            testParameters(
                $( "${it.nexus.bundle.groupId}:${it.nexus.bundle.artifactId}:zip:bundle" )
            )
        ).load();
    }

    public static final String FELIX_WEBCONSOLE =
        "org/apache/felix/org.apache.felix.webconsole/3.0.0/org.apache.felix.webconsole-3.0.0.jar";

    public static final String OSGI_COMPENDIUM =
        "org/apache/felix/org.osgi.compendium/1.4.0/org.osgi.compendium-1.4.0.jar";

    public static final String GERONIMO_SERVLET =
        "org/apache/geronimo/specs/geronimo-servlet_3.0_spec/1.0/geronimo-servlet_3.0_spec-1.0.jar";

    public static final String PORTLET_API =
        "org/apache/portals/portlet-api_2.0_spec/1.0/portlet-api_2.0_spec-1.0.jar";

    public ObrITSupport( final String nexusBundleCoordinates )
    {
        super( nexusBundleCoordinates );
    }

    @Override
    protected NexusBundleConfiguration configureNexus( final NexusBundleConfiguration configuration )
    {
        return configuration.addPlugins(
            artifactResolver().resolvePluginFromDependencyManagement(
                "org.sonatype.nexus.plugins", "nexus-obr-plugin"
            )
        );
    }

    protected Repositories repositories()
    {
        return client().getSubsystem( Repositories.class );
    }

    protected Content content()
    {
        return client().getSubsystem( Content.class );
    }

    protected void deployUsingObrIntoFelix( final String repoId )
        throws Exception
    {
        final File felixHome = util.resolveFile( "target/org.apache.felix.main.distribution-3.2.2" );
        final File felixRepo = util.resolveFile( "target/felix-repo" );
        final File felixConfig = testData().resolveFile( "felix.properties" );

        // ensure we have an obr.xml
        final Content content = content();
        final Location obrLocation = new Location( repoId, ".meta/obr.xml" );
        content.download(
            obrLocation,
            new File( testIndex().getDirectory( "downloads" ), repoId + "-obr.xml" )
        );

        FileUtils.deleteDirectory( new File( felixHome, "felix-cache" ) );
        FileUtils.deleteDirectory( new File( felixRepo, ".meta" ) );

        final ProcessBuilder pb = new ProcessBuilder(
            "java", "-Dfelix.felix.properties=" + felixConfig.toURI(), "-jar", "bin/felix.jar"
        );
        pb.directory( felixHome );
        pb.redirectErrorStream( true );
        final Process p = pb.start();

        final Object lock = new Object();

        final Thread t = new Thread( new Runnable()
        {
            public void run()
            {
                // just a safeguard, if felix get stuck kill everything
                try
                {
                    synchronized ( lock )
                    {
                        lock.wait( 5 * 1000 * 60 );
                    }
                }
                catch ( final InterruptedException e )
                {
                    // ignore
                }
                p.destroy();
            }
        } );
        t.setDaemon( true );
        t.start();

        synchronized ( lock )
        {
            final InputStream input = p.getInputStream();
            final OutputStream output = p.getOutputStream();
            waitFor( input, "g!" );

            output.write(
                ( "obr:repos add " + nexus().getUrl() + "content/" + obrLocation.toContentPath() + "\r\n" ).getBytes()
            );
            output.flush();
            waitFor( input, "g!" );

            output.write(
                ( "obr:repos remove http://felix.apache.org/obr/releases.xml\r\n" ).getBytes()
            );
            output.flush();
            waitFor( input, "g!" );

            output.write(
                ( "obr:repos list\r\n" ).getBytes()
            );
            output.flush();
            waitFor( input, "g!" );

            output.write( "obr:deploy -s org.apache.felix.webconsole\r\n".getBytes() );
            output.flush();
            waitFor( input, "done." );

            p.destroy();

            lock.notifyAll();
        }
    }

    private void waitFor( final InputStream input, final String expectedLine )
        throws Exception
    {
        final long startMillis = System.currentTimeMillis();
        final StringBuilder content = new StringBuilder();
        do
        {
            final int available = input.available();
            if ( available > 0 )
            {
                final byte[] bytes = new byte[available];
                input.read( bytes );
                final String current = new String( bytes );
                System.out.print( current );
                content.append( current );
                Thread.yield();
            }
            else if ( System.currentTimeMillis() - startMillis > 5 * 60 * 1000 )
            {
                throw new InterruptedException(); // waited for more than 5 minutes
            }
            else
            {
                try
                {
                    Thread.sleep( 100 );
                }
                catch ( final InterruptedException e )
                {
                    // continue...
                }
            }
        }
        while ( content.indexOf( expectedLine ) == -1 );
        System.out.println();
    }

    protected void upload( final String repositoryId, final String path )
        throws IOException
    {
        content().upload( new Location( repositoryId, path ), util.resolveFile( "target/felix-repo/" + path ) );
    }

    protected File download( final String repositoryId, final String path )
        throws IOException
    {
        final File downloaded = new File( testIndex().getDirectory( "downloads" ), path );
        content().download( new Location( repositoryId, path ), downloaded );
        return downloaded;
    }

    protected void deployUsingMaven( final String projectName, final String repositoryId )
        throws VerificationException
    {
        final File projectToBuildSource = testData().resolveFile( projectName );
        final File mavenSettingsSource = testData().resolveFile( "settings.xml" );

        final File projectToBuildTarget = testIndex().getDirectory( "maven/" + projectName );
        final File mavenSettingsTarget = new File( testIndex().getDirectory( "maven" ), "settings.xml" );

        final Properties properties = new Properties();
        properties.setProperty( "nexus-base-url", nexus().getUrl().toExternalForm() );
        properties.setProperty( "nexus-repository-id", repositoryId );

        tasks().copy().directory( file( projectToBuildSource ) )
            .filterUsing( properties )
            .to().directory( file( projectToBuildTarget ) ).run();
        tasks().copy().file( file( mavenSettingsSource ) )
            .filterUsing( properties )
            .to().file( file( mavenSettingsTarget ) ).run();

        final File mavenHome = util.resolveFile( "target/apache-maven-3.0.4" );
        final File localRepo = util.resolveFile( "target/maven/fake-repo" );

        tasks().chmod( file( new File( mavenHome, "bin" ) ) ).include( "mvn" ).permissions( "755" ).run();

        System.setProperty( "maven.home", mavenHome.getAbsolutePath() );
        final Verifier verifier = new Verifier( projectToBuildTarget.getAbsolutePath(), false );
        verifier.setAutoclean( true );
        verifier.setLogFileName( "maven.log" );

        verifier.setLocalRepo( localRepo.getAbsolutePath() );
        verifier.setMavenDebug( true );
        verifier.setCliOptions( Arrays.asList( "-s " + mavenSettingsTarget.getAbsolutePath() ) );

        verifier.resetStreams();

        verifier.executeGoal( "deploy" );
        verifier.verifyErrorFreeLog();

        testIndex().recordLink( "maven.log", new File( projectToBuildTarget, "maven.log" ) );
    }

    protected String repositoryIdForTest()
    {
        String methodName = testName.getMethodName();
        if ( methodName.contains( "[" ) )
        {
            return methodName.substring( 0, methodName.indexOf( "[" ) );
        }
        return methodName;
    }

}
