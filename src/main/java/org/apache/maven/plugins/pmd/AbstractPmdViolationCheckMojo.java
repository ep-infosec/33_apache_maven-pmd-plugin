package org.apache.maven.plugins.pmd;

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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Base class for mojos that check if there were any PMD violations.
 *
 * @param <D> type of the check, e.g. {@link org.apache.maven.plugins.pmd.model.Violation}
 * or {@link org.apache.maven.plugins.pmd.model.Duplication}.
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 * @version $Id$
 */
public abstract class AbstractPmdViolationCheckMojo<D>
    extends AbstractMojo
{
    /**
     * The location of the XML report to check, as generated by the PMD report.
     */
    @Parameter( property = "project.build.directory", required = true )
    private File targetDirectory;

    /**
     * Whether to fail the build if the validation check fails.
     * The properties {@code failurePriority} and {@code maxAllowedViolations} control
     * under which conditions exactly the build should be failed.
     */
    @Parameter( property = "pmd.failOnViolation", defaultValue = "true", required = true )
    protected boolean failOnViolation;

    /**
     * Whether to build an aggregated report at the root, or build individual reports.
     *
     * @since 2.2
     * @deprecated since 3.15.0 Use the goal <code>pmd:aggregate-check</code> or
     * <code>pmd:aggregate-cpd-check</code> instead.
     */
    @Parameter( property = "aggregate", defaultValue = "false" )
    @Deprecated
    protected boolean aggregate;

    /**
     * Print details of check failures to build output.
     */
    @Parameter( property = "pmd.verbose", defaultValue = "false" )
    private boolean verbose;

    /**
     * Print details of errors that cause build failure
     *
     * @since 3.0
     */
    @Parameter( property = "pmd.printFailingErrors", defaultValue = "false" )
    private boolean printFailingErrors;

    /**
     * File that lists classes and rules to be excluded from failures.
     * For PMD, this is a properties file. For CPD, this
     * is a text file that contains comma-separated lists of classes
     * that are allowed to duplicate.
     *
     * @since 3.0
     */
    @Parameter( property = "pmd.excludeFromFailureFile", defaultValue = "" )
    private String excludeFromFailureFile;

    /**
     * The maximum number of failures allowed before execution fails.
     * Used in conjunction with {@code failOnViolation=true} and utilizes {@code failurePriority}.
     * This value has no meaning if {@code failOnViolation=false}.
     * If the number of failures is greater than this number, the build will be failed.
     * If the number of failures is less than or equal to this value,
     * then the build will not be failed.
     *
     * @since 3.10.0
     */
    @Parameter( property = "pmd.maxAllowedViolations", defaultValue = "0" )
    private int maxAllowedViolations;

    /** Helper to exclude violations from the result. */
    private final ExcludeFromFile<D> excludeFromFile;

    /**
     * Initialize this abstact check mojo by giving the correct ExcludeFromFile helper.
     * @param excludeFromFile the needed helper, for the specific violation type
     */
    protected AbstractPmdViolationCheckMojo( ExcludeFromFile<D> excludeFromFile )
    {
        this.excludeFromFile = excludeFromFile;
    }

    /**
     * The project to analyze.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    protected MavenProject project;

    protected void executeCheck( final String filename, final String tagName, final String key,
                                 final int failurePriority )
        throws MojoFailureException, MojoExecutionException
    {
        if ( aggregate && !project.isExecutionRoot() )
        {
            return;
        }

        if ( !isAggregator() && "pom".equalsIgnoreCase( project.getPackaging() ) )
        {
            return;
        }

        excludeFromFile.loadExcludeFromFailuresData( excludeFromFailureFile );
        final File outputFile = new File( targetDirectory, filename );

        if ( outputFile.exists() )
        {
            getLog().info( "PMD version: " + AbstractPmdReport.getPmdVersion() );

            try
            {
                final ViolationDetails<D> violations = getViolations( outputFile, failurePriority );

                final List<D> failures = violations.getFailureDetails();
                final List<D> warnings = violations.getWarningDetails();

                if ( verbose )
                {
                    printErrors( failures, warnings );
                }

                final int failureCount = failures.size();
                final int warningCount = warnings.size();

                final String message = getMessage( failureCount, warningCount, key, outputFile );

                getLog().debug( "PMD failureCount: " + failureCount + ", warningCount: " + warningCount );

                if ( failureCount > getMaxAllowedViolations() && isFailOnViolation() )
                {
                    throw new MojoFailureException( message );
                }

                this.getLog().info( message );

                if ( failureCount > 0 && isFailOnViolation() && failureCount <= getMaxAllowedViolations() )
                {
                    this.getLog().info( "The build is not failed, since " + getMaxAllowedViolations()
                        + " violations are allowed (maxAllowedViolations)." );
                }
            }
            catch ( final IOException | XmlPullParserException e )
            {
                throw new MojoExecutionException(
                                                  "Unable to read PMD results xml: " + outputFile.getAbsolutePath(),
                                                  e );
            }
        }
        else
        {
            throw new MojoFailureException( "Unable to perform check, " + "unable to find " + outputFile );
        }
    }

    /**
     * Method for collecting the violations found by the PMD tool
     *
     * @param analysisFile
     * @param failurePriority
     * @return an int that specifies the number of violations found
     * @throws XmlPullParserException
     * @throws IOException
     */
    private ViolationDetails<D> getViolations( final File analysisFile, final int failurePriority )
        throws XmlPullParserException, IOException
    {
        final List<D> failures = new ArrayList<>();
        final List<D> warnings = new ArrayList<>();

        final List<D> violations = getErrorDetails( analysisFile );

        for ( final D violation : violations )
        {
            final int priority = getPriority( violation );
            if ( priority <= failurePriority && !excludeFromFile.isExcludedFromFailure( violation ) )
            {
                failures.add( violation );
                if ( printFailingErrors )
                {
                    printError( violation, "Failure" );
                }
            }
            else
            {
                warnings.add( violation );
            }
        }

        final ViolationDetails<D> details = newViolationDetailsInstance();
        details.setFailureDetails( failures );
        details.setWarningDetails( warnings );
        return details;
    }

    protected abstract int getPriority( D errorDetail );

    protected abstract ViolationDetails<D> newViolationDetailsInstance();

    /**
     * Prints the warnings and failures
     *
     * @param failures list of failures
     * @param warnings list of warnings
     */
    protected void printErrors( final List<D> failures, final List<D> warnings )
    {
        for ( final D warning : warnings )
        {
            printError( warning, "Warning" );
        }

        for ( final D failure : failures )
        {
            printError( failure, "Failure" );
        }
    }

    /**
     * Gets the output message
     *
     * @param failureCount
     * @param warningCount
     * @param key
     * @param outputFile
     * @return
     */
    private String getMessage( final int failureCount, final int warningCount, final String key, final File outputFile )
    {
        final StringBuilder message = new StringBuilder( 256 );
        if ( failureCount > 0 || warningCount > 0 )
        {
            if ( failureCount > 0 )
            {
                message.append( "You have " ).append( failureCount ).append( " " ).append( key ).
                  append( failureCount > 1 ? "s" : "" );
            }

            if ( warningCount > 0 )
            {
                if ( failureCount > 0 )
                {
                    message.append( " and " );
                }
                else
                {
                    message.append( "You have " );
                }
                message.append( warningCount ).append( " warning" ).append( warningCount > 1 ? "s" : "" );
            }

            message.append( ". For more details see: " ).append( outputFile.getAbsolutePath() );
        }
        return message.toString();
    }

    /**
     * Formats the failure details and prints them as an INFO message
     *
     * @param item either a {@link org.apache.maven.plugins.pmd.model.Violation} from PMD
     * or a {@link org.apache.maven.plugins.pmd.model.Duplication} from CPD
     * @param severity the found issue is prefixed with the given severity, usually "Warning" or "Failure".
     */
    protected abstract void printError( D item, String severity );

    /**
     * Gets the attributes and text for the violation tag and puts them in a HashMap
     *
     * @param analysisFile the xml output from PMD or CPD
     * @return all PMD {@link org.apache.maven.plugins.pmd.model.Violation}s
     * or CPD {@link org.apache.maven.plugins.pmd.model.Duplication}s.
     * @throws XmlPullParserException if the analysis file contains invalid XML
     * @throws IOException if the analysis file could be read
     */
    protected abstract List<D> getErrorDetails( File analysisFile )
        throws XmlPullParserException, IOException;

    public boolean isFailOnViolation()
    {
        return failOnViolation;
    }

    public Integer getMaxAllowedViolations()
    {
        return maxAllowedViolations;
    }

    protected boolean isAggregator()
    {
        // returning here aggregate for backwards compatibility
        return aggregate;
    }
}