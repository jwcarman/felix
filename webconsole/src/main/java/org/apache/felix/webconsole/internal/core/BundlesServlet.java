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
package org.apache.felix.webconsole.internal.core;


import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.bundlerepository.*;
import org.apache.felix.webconsole.internal.BaseWebConsolePlugin;
import org.apache.felix.webconsole.internal.Util;
import org.apache.felix.webconsole.internal.servlet.OsgiManager;
import org.json.JSONException;
import org.json.JSONWriter;
import org.osgi.framework.*;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.log.LogService;
import org.osgi.service.obr.RepositoryAdmin;
import org.osgi.service.packageadmin.ExportedPackage;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.service.startlevel.StartLevel;


/**
 * The <code>BundlesServlet</code> TODO
 */
public class BundlesServlet extends BaseWebConsolePlugin
{

    public static final String NAME = "bundles";

    public static final String LABEL = "Bundles";

    public static final String BUNDLE_ID = "bundleId";

    private static final String REPOSITORY_ADMIN_NAME = RepositoryAdmin.class.getName();

    // bootdelegation property entries. wildcards are converted to package
    // name prefixes. whether an entry is a wildcard or not is set as a flag
    // in the bootPkgWildcards array.
    // see #activate and #isBootDelegated
    private String[] bootPkgs;

    // a flag for each entry in bootPkgs indicating whether the respective
    // entry was declared as a wildcard or not
    // see #activate and #isBootDelegated
    private boolean[] bootPkgWildcards;


    public void activate( BundleContext bundleContext )
    {
        super.activate( bundleContext );

        // bootdelegation property parsing from Apache Felix R4SearchPolicyCore
        String bootDelegation = bundleContext.getProperty( Constants.FRAMEWORK_BOOTDELEGATION );
        bootDelegation = ( bootDelegation == null ) ? "java.*" : bootDelegation + ",java.*";
        StringTokenizer st = new StringTokenizer( bootDelegation, " ," );
        bootPkgs = new String[st.countTokens()];
        bootPkgWildcards = new boolean[bootPkgs.length];
        for ( int i = 0; i < bootPkgs.length; i++ )
        {
            bootDelegation = st.nextToken();
            if ( bootDelegation.endsWith( "*" ) )
            {
                bootPkgWildcards[i] = true;
                bootDelegation = bootDelegation.substring( 0, bootDelegation.length() - 1 );
            }
            bootPkgs[i] = bootDelegation;
        }
    }


    public String getLabel()
    {
        return NAME;
    }


    public String getTitle()
    {
        return LABEL;
    }


    protected void doGet( HttpServletRequest request, HttpServletResponse response ) throws ServletException,
        IOException
    {

        String info = request.getPathInfo();
        if ( info.endsWith( ".json" ) )
        {
            info = info.substring( 0, info.length() - 5 );
            if ( getLabel().equals( info.substring( 1 ) ) )
            {
                // should return info on all bundles
            }
            else
            {
                Bundle bundle = getBundle( info );
                if ( bundle != null )
                {
                    // bundle properties

                    response.setContentType( "application/json" );
                    response.setCharacterEncoding( "UTF-8" );

                    PrintWriter pw = response.getWriter();
                    JSONWriter jw = new JSONWriter( pw );
                    try
                    {
                        performAction( jw, bundle );
                    }
                    catch ( JSONException je )
                    {
                        throw new IOException( je.toString() );
                    }
                }
            }

            // nothing more to do
            return;
        }

        super.doGet( request, response );
    }


    protected void doPost( HttpServletRequest req, HttpServletResponse resp ) throws ServletException, IOException
    {
        String action = req.getParameter( "action" );
        if ( "refreshPackages".equals( action ) )
        {
            getPackageAdmin().refreshPackages( null );
        }

        boolean success = false;
        Bundle bundle = getBundle( req.getPathInfo() );
        long bundleId = -1;

        if ( bundle != null )
        {
            bundleId = bundle.getBundleId();
            if ( action == null )
            {
                success = true;
            }
            else if ( "start".equals( action ) )
            {
                // start bundle
                success = true;
                try
                {
                    bundle.start();
                }
                catch ( BundleException be )
                {
                    getLog().log( LogService.LOG_ERROR, "Cannot start", be );
                }
            }
            else if ( "stop".equals( action ) )
            {
                // stop bundle
                success = true;
                try
                {
                    bundle.stop();
                }
                catch ( BundleException be )
                {
                    getLog().log( LogService.LOG_ERROR, "Cannot stop", be );
                }
            }
            else if ( "refresh".equals( action ) )
            {
                // refresh bundle wiring
                refresh( bundle );
                success = true;
            }
            else if ( "uninstall".equals( action ) )
            {
                // uninstall bundle
                success = true;
                try
                {
                    bundle.uninstall();
                    bundle = null; // bundle has gone !
                }
                catch ( BundleException be )
                {
                    getLog().log( LogService.LOG_ERROR, "Cannot uninstall", be );
                }
            }
        }

        if ( "refreshPackages".equals( action ) )
        {
            success = true;
            getPackageAdmin().refreshPackages( null );

            // refresh completely
            bundle = null;
            bundleId = -1;
        }

        if ( success )
        {
            // redirect or 200
            resp.setStatus( HttpServletResponse.SC_OK );
            JSONWriter jw = new JSONWriter( resp.getWriter() );
            try
            {
                if ( bundle != null )
                {
                    bundleInfo( jw, bundle, true );
                }
                else if ( bundleId >= 0 )
                {
                    jw.object();
                    jw.key( "bundleId" );
                    jw.value( bundleId );
                    jw.endObject();
                }
                else
                {
                    jw.object();
                    jw.key( "reload" );
                    jw.value( true );
                    jw.endObject();
                }
            }
            catch ( JSONException je )
            {
                throw new IOException( je.toString() );
            }
        }
        else
        {
            super.doPost( req, resp );
        }
    }


    private Bundle getBundle( String pathInfo )
    {
        // only use last part of the pathInfo
        pathInfo = pathInfo.substring( pathInfo.lastIndexOf( '/' ) + 1 );

        // assume bundle Id
        try
        {
            final long bundleId = Long.parseLong( pathInfo );
            if ( bundleId >= 0 )
            {
                return getBundleContext().getBundle( bundleId );
            }
        }
        catch ( NumberFormatException nfe )
        {
            // check if this follows the pattern {symbolic-name}[:{version}]
            final int pos = pathInfo.indexOf(':');
            final String symbolicName;
            final String version;
            if ( pos == -1 ) {
                symbolicName = pathInfo;
                version = null;
            } else {
                symbolicName = pathInfo.substring(0, pos);
                version = pathInfo.substring(pos+1);
            }

            // search
            final Bundle[] bundles = getBundleContext().getBundles();
            for(int i=0; i<bundles.length; i++)
            {
                final Bundle bundle = bundles[i];
                // check symbolic name first
                if ( symbolicName.equals(bundle.getSymbolicName()) )
                {
                    if ( version == null || version.equals(bundle.getHeaders().get(Constants.BUNDLE_VERSION)) )
                    {
                        return bundle;
                    }
                }
            }
        }


        return null;
    }


    private void renderBundleInfoCount( final PrintWriter pw, String msg, int count )
    {
        pw.print( "<td class='content'>" );
        pw.print( msg );
        pw.print( " : " );
        pw.print( count );
        pw.print( " Bundle" );
        if ( count != 1 )
            pw.print( 's' );
        pw.println( "</td>" );
    }


    protected void renderContent( HttpServletRequest request, HttpServletResponse response ) throws IOException
    {
        Bundle bundle = getBundle( request.getPathInfo() );
        Bundle[] bundles = ( bundle != null ) ? new Bundle[]
            { bundle } : this.getBundles();

        PrintWriter pw = response.getWriter();

        String appRoot = ( String ) request.getAttribute( OsgiManager.ATTR_APP_ROOT );
        pw.println( "<script src='" + appRoot + "/res/ui/datatable.js' language='JavaScript'></script>" );
        pw.println( "<script src='" + appRoot + "/res/ui/bundles.js' language='JavaScript'></script>" );

        if ( bundles != null )
        {
            int active = 0, installed = 0, resolved = 0;
            for ( int i = 0; i < bundles.length; i++ )
            {
                switch ( bundles[i].getState() )
                {
                    case Bundle.ACTIVE:
                        active++;
                        break;
                    case Bundle.INSTALLED:
                        installed++;
                        break;
                    case Bundle.RESOLVED:
                        resolved++;
                        break;
                }
            }

            pw.println( "<table class='content' cellpadding='0' cellspacing='0' width='100%'><tbody>" );
            pw.println( "<tr class='content'>" );
            renderBundleInfoCount( pw, "Total", bundles.length );
            renderBundleInfoCount( pw, "Active", active );
            renderBundleInfoCount( pw, "Resolved", resolved );
            renderBundleInfoCount( pw, "Installed", installed );
            pw.println( "</tr></tbody></table>" );
        }

        Util.startScript( pw );
        pw.println( "var bundleListData = " );
        JSONWriter jw = new JSONWriter( pw );
        try
        {
            jw.object();

            jw.key( "startLevel" );
            jw.value( getStartLevel().getInitialBundleStartLevel() );

            jw.key( "numActions" );
            jw.value( 4 );

            boolean details = ( bundle != null );

            if ( bundles != null && bundles.length > 0 )
            {
                Util.sort( bundles );

                jw.key( "data" );

                jw.array();

                for ( int i = 0; i < bundles.length; i++ )
                {
                    bundleInfo( jw, bundles[i], details );
                }

                jw.endArray();

            }
            else
            {
                jw.key( "error" );
                jw.value( "No Bundles installed currently" );
            }

            jw.endObject();

        }
        catch ( JSONException je )
        {
            throw new IOException( je.toString() );
        }

        pw.println( ";" );
        pw.println( "renderBundle( bundleListData );" );
        Util.endScript( pw );
    }


    private void bundleInfo( JSONWriter jw, Bundle bundle, boolean details ) throws JSONException
    {
        jw.object();
        jw.key( "id" );
        jw.value( bundle.getBundleId() );
        jw.key( "name" );
        jw.value( Util.getName( bundle ) );
        jw.key( "state" );
        jw.value( toStateString( bundle.getState() ) );

        jw.key( "actions" );
        jw.array();

        if ( bundle.getBundleId() == 0 )
        {
            jw.value( false );
            jw.value( false );
            jw.value( false );
            jw.value( false );
        }
        else
        {
            action( jw, hasStart( bundle ), "start", "Start", null );
            action( jw, hasStop( bundle ), "stop", "Stop", null );
            action( jw, true, "refresh", "Refresh", "Refresh Package Imports" );
            action( jw, hasUninstall( bundle ), "uninstall", "Uninstall", null );
        }
        jw.endArray();

        if ( details )
        {
            bundleDetails( jw, bundle );
        }

        jw.endObject();
    }


    protected Bundle[] getBundles()
    {
        return getBundleContext().getBundles();
    }


    private String toStateString( int bundleState )
    {
        switch ( bundleState )
        {
            case Bundle.INSTALLED:
                return "Installed";
            case Bundle.RESOLVED:
                return "Resolved";
            case Bundle.STARTING:
                return "Starting";
            case Bundle.ACTIVE:
                return "Active";
            case Bundle.STOPPING:
                return "Stopping";
            case Bundle.UNINSTALLED:
                return "Uninstalled";
            default:
                return "Unknown: " + bundleState;
        }
    }


    private void action( JSONWriter jw, boolean enabled, String op, String opLabel, String title ) throws JSONException
    {
        jw.object();
        jw.key( "enabled" ).value( enabled );
        jw.key( "name" ).value( opLabel );
        jw.key( "link" ).value( op );
        if (title != null) {
            jw.key( "title" ).value( title );
        }
        jw.endObject();
    }


    private boolean hasStart( Bundle bundle )
    {
        return bundle.getState() == Bundle.INSTALLED || bundle.getState() == Bundle.RESOLVED;
    }


    private boolean hasStop( Bundle bundle )
    {
        return bundle.getState() == Bundle.ACTIVE;
    }


    private boolean hasUninstall( Bundle bundle )
    {
        return bundle.getState() == Bundle.INSTALLED || bundle.getState() == Bundle.RESOLVED
            || bundle.getState() == Bundle.ACTIVE;

    }


    private void performAction( JSONWriter jw, Bundle bundle ) throws JSONException
    {
        jw.object();
        jw.key( BUNDLE_ID );
        jw.value( bundle.getBundleId() );

        bundleDetails( jw, bundle );

        jw.endObject();
    }


    private void bundleDetails( JSONWriter jw, Bundle bundle ) throws JSONException
    {
        Dictionary headers = bundle.getHeaders();

        jw.key( "props" );
        jw.array();
        keyVal( jw, "Symbolic Name", bundle.getSymbolicName() );
        keyVal( jw, "Version", headers.get( Constants.BUNDLE_VERSION ) );
        keyVal( jw, "Location", bundle.getLocation() );
        keyVal( jw, "Last Modification", new Date( bundle.getLastModified() ) );

        String docUrl = ( String ) headers.get( Constants.BUNDLE_DOCURL );
        if ( docUrl != null )
        {
            docUrl = "<a href=\"" + docUrl + "\" target=\"_blank\">" + docUrl + "</a>";
            keyVal( jw, "Bundle Documentation", docUrl );
        }

        keyVal( jw, "Vendor", headers.get( Constants.BUNDLE_VENDOR ) );
        keyVal( jw, "Copyright", headers.get( Constants.BUNDLE_COPYRIGHT ) );
        keyVal( jw, "Description", headers.get( Constants.BUNDLE_DESCRIPTION ) );

        keyVal( jw, "Start Level", getStartLevel( bundle ) );

        keyVal( jw, "Bundle Classpath", headers.get( Constants.BUNDLE_CLASSPATH ) );

        if ( bundle.getState() == Bundle.INSTALLED )
        {
            listImportExportsUnresolved( jw, bundle );
        }
        else
        {
            listImportExport( jw, bundle );
        }

        listServices( jw, bundle );

        jw.endArray();
    }


    private Integer getStartLevel( Bundle bundle )
    {
        StartLevel sl = getStartLevel();
        return ( sl != null ) ? new Integer( sl.getBundleStartLevel( bundle ) ) : null;
    }


    private void listImportExport( JSONWriter jw, Bundle bundle ) throws JSONException
    {
        PackageAdmin packageAdmin = getPackageAdmin();
        if ( packageAdmin == null )
        {
            return;
        }

        Map usingBundles = new TreeMap();

        ExportedPackage[] exports = packageAdmin.getExportedPackages( bundle );
        if ( exports != null && exports.length > 0 )
        {
            // do alphabetical sort
            Arrays.sort( exports, new Comparator()
            {
                public int compare( ExportedPackage p1, ExportedPackage p2 )
                {
                    return p1.getName().compareTo( p2.getName() );
                }


                public int compare( Object o1, Object o2 )
                {
                    return compare( ( ExportedPackage ) o1, ( ExportedPackage ) o2 );
                }
            } );

            StringBuffer val = new StringBuffer();
            for ( int j = 0; j < exports.length; j++ )
            {
                ExportedPackage export = exports[j];
                printExport( val, export.getName(), export.getVersion() );
                Bundle[] ubList = export.getImportingBundles();
                if ( ubList != null )
                {
                    for ( int i = 0; i < ubList.length; i++ )
                    {
                        Bundle ub = ubList[i];
                        usingBundles.put( ub.getSymbolicName(), ub );
                    }
                }
            }
            keyVal( jw, "Exported Packages", val.toString() );
        }
        else
        {
            keyVal( jw, "Exported Packages", "None" );
        }

        exports = packageAdmin.getExportedPackages( ( Bundle ) null );
        if ( exports != null && exports.length > 0 )
        {
            // collect import packages first
            final List imports = new ArrayList();
            for ( int i = 0; i < exports.length; i++ )
            {
                final ExportedPackage ep = exports[i];
                final Bundle[] importers = ep.getImportingBundles();
                for ( int j = 0; importers != null && j < importers.length; j++ )
                {
                    if ( importers[j].getBundleId() == bundle.getBundleId() )
                    {
                        imports.add( ep );

                        break;
                    }
                }
            }
            // now sort
            StringBuffer val = new StringBuffer();
            if ( imports.size() > 0 )
            {
                final ExportedPackage[] packages = ( ExportedPackage[] ) imports.toArray( new ExportedPackage[imports
                    .size()] );
                Arrays.sort( packages, new Comparator()
                {
                    public int compare( ExportedPackage p1, ExportedPackage p2 )
                    {
                        return p1.getName().compareTo( p2.getName() );
                    }


                    public int compare( Object o1, Object o2 )
                    {
                        return compare( ( ExportedPackage ) o1, ( ExportedPackage ) o2 );
                    }
                } );
                // and finally print out
                for ( int i = 0; i < packages.length; i++ )
                {
                    ExportedPackage ep = packages[i];
                    printImport( val, ep.getName(), ep.getVersion(), false, ep );
                }
            }
            else
            {
                // add description if there are no imports
                val.append( "None" );
            }

            keyVal( jw, "Imported Packages", val.toString() );
        }

        if ( !usingBundles.isEmpty() )
        {
            StringBuffer val = new StringBuffer();
            for ( Iterator ui = usingBundles.values().iterator(); ui.hasNext(); )
            {
                Bundle usingBundle = ( Bundle ) ui.next();
                val.append( getBundleDescriptor( usingBundle ) );
                val.append( "<br />" );
            }
            keyVal( jw, "Importing Bundles", val.toString() );
        }
    }


    private void listImportExportsUnresolved( JSONWriter jw, Bundle bundle ) throws JSONException
    {
        Dictionary dict = bundle.getHeaders();

        String target = ( String ) dict.get( Constants.EXPORT_PACKAGE );
        if ( target != null )
        {
            R4Package[] pkgs = R4Package.parseImportOrExportHeader( target );
            if ( pkgs != null && pkgs.length > 0 )
            {
                // do alphabetical sort
                Arrays.sort( pkgs, new Comparator()
                {
                    public int compare( R4Package p1, R4Package p2 )
                    {
                        return p1.getName().compareTo( p2.getName() );
                    }


                    public int compare( Object o1, Object o2 )
                    {
                        return compare( ( R4Package ) o1, ( R4Package ) o2 );
                    }
                } );

                StringBuffer val = new StringBuffer();
                for ( int i = 0; i < pkgs.length; i++ )
                {
                    R4Export export = new R4Export( pkgs[i] );
                    printExport( val, export.getName(), export.getVersion() );
                }
                keyVal( jw, "Exported Packages", val.toString() );
            }
            else
            {
                keyVal( jw, "Exported Packages", "None" );
            }
        }

        target = ( String ) dict.get( Constants.IMPORT_PACKAGE );
        if ( target != null )
        {
            R4Package[] pkgs = R4Package.parseImportOrExportHeader( target );
            if ( pkgs != null && pkgs.length > 0 )
            {
                Map imports = new TreeMap();
                for ( int i = 0; i < pkgs.length; i++ )
                {
                    R4Package pkg = pkgs[i];
                    imports.put( pkg.getName(), new R4Import( pkg ) );
                }

                // collect import packages first
                final Map candidates = new HashMap();
                PackageAdmin packageAdmin = getPackageAdmin();
                if ( packageAdmin != null )
                {
                    ExportedPackage[] exports = packageAdmin.getExportedPackages( ( Bundle ) null );
                    if ( exports != null && exports.length > 0 )
                    {

                        for ( int i = 0; i < exports.length; i++ )
                        {
                            final ExportedPackage ep = exports[i];

                            R4Import imp = ( R4Import ) imports.get( ep.getName() );
                            if ( imp != null && imp.isSatisfied( toR4Export( ep ) ) )
                            {
                                candidates.put( ep.getName(), ep );
                            }
                        }
                    }
                }

                // now sort
                StringBuffer val = new StringBuffer();
                if ( imports.size() > 0 )
                {
                    for ( Iterator ii = imports.values().iterator(); ii.hasNext(); )
                    {
                        R4Import r4Import = ( R4Import ) ii.next();
                        ExportedPackage ep = ( ExportedPackage ) candidates.get( r4Import.getName() );

                        // if there is no matching export, check whether this
                        // bundle has the package, ignore the entry in this case
                        if ( ep == null )
                        {
                            String path = r4Import.getName().replace( '.', '/' );
                            if ( bundle.getResource( path ) != null )
                            {
                                continue;
                            }
                        }

                        printImport( val, r4Import.getName(), r4Import.getVersion(), r4Import.isOptional(), ep );
                    }
                }
                else
                {
                    // add description if there are no imports
                    val.append( "None" );
                }

                keyVal( jw, "Imported Packages", val.toString() );
            }
        }
    }


    private void listServices( JSONWriter jw, Bundle bundle ) throws JSONException
    {
        ServiceReference[] refs = bundle.getRegisteredServices();
        if ( refs == null || refs.length == 0 )
        {
            return;
        }

        for ( int i = 0; i < refs.length; i++ )
        {
            String key = "Service ID " + refs[i].getProperty( Constants.SERVICE_ID );

            StringBuffer val = new StringBuffer();

            appendProperty( val, refs[i], Constants.OBJECTCLASS, "Types" );
            appendProperty( val, refs[i], Constants.SERVICE_PID, "PID" );
            appendProperty( val, refs[i], ConfigurationAdmin.SERVICE_FACTORYPID, "Factory PID" );
            appendProperty( val, refs[i], ComponentConstants.COMPONENT_NAME, "Component Name" );
            appendProperty( val, refs[i], ComponentConstants.COMPONENT_ID, "Component ID" );
            appendProperty( val, refs[i], ComponentConstants.COMPONENT_FACTORY, "Component Factory" );
            appendProperty( val, refs[i], Constants.SERVICE_DESCRIPTION, "Description" );
            appendProperty( val, refs[i], Constants.SERVICE_VENDOR, "Vendor" );

            keyVal( jw, key, val.toString() );
        }
    }


    private void appendProperty( StringBuffer dest, ServiceReference ref, String name, String label )
    {
        Object value = ref.getProperty( name );
        if ( value instanceof Object[] )
        {
            Object[] values = ( Object[] ) value;
            dest.append( label ).append( ": " );
            for ( int j = 0; j < values.length; j++ )
            {
                if ( j > 0 )
                    dest.append( ", " );
                dest.append( values[j] );
            }
            dest.append( "<br />" ); // assume HTML use of result
        }
        else if ( value != null )
        {
            dest.append( label ).append( ": " ).append( value ).append( "<br />" );
        }
    }


    private void keyVal( JSONWriter jw, String key, Object value ) throws JSONException
    {
        if ( key != null && value != null )
        {
            jw.object();
            jw.key( "key" );
            jw.value( key );
            jw.key( "value" );
            jw.value( value );
            jw.endObject();
        }
    }


    private void printExport( StringBuffer val, String name, Version version )
    {
        boolean bootDel = isBootDelegated( name );
        if ( bootDel )
        {
            val.append( "!! " );
        }

        val.append( name );
        val.append( ",version=" );
        val.append( version );

        if ( bootDel )
        {
            val.append( " -- Overwritten by Boot Delegation" );
        }

        val.append( "<br />" );
    }


    private void printImport( StringBuffer val, String name, Version version, boolean optional, ExportedPackage export )
    {
        boolean bootDel = isBootDelegated( name );
        boolean isSpan = bootDel || export == null;

        if ( isSpan )
        {
            val.append( "!! " );
        }

        val.append( name );
        val.append( ",version=" ).append( version );
        val.append( " from " );

        if ( export != null )
        {
            val.append( getBundleDescriptor( export.getExportingBundle() ) );

            if ( bootDel )
            {
                val.append( " -- Overwritten by Boot Delegation" );
            }
        }
        else
        {
            val.append( " -- Cannot be resolved" );

            if ( optional )
            {
                val.append( " but is not required" );
            }

            if ( bootDel )
            {
                val.append( " and overwritten by Boot Delegation" );
            }
        }

        val.append( "<br />" );
    }


    // returns true if the package is listed in the bootdelegation property
    private boolean isBootDelegated( String pkgName )
    {

        // bootdelegation analysis from Apache Felix R4SearchPolicyCore

        // Only consider delegation if we have a package name, since
        // we don't want to promote the default package. The spec does
        // not take a stand on this issue.
        if ( pkgName.length() > 0 )
        {

            // Delegate any packages listed in the boot delegation
            // property to the parent class loader.
            for ( int i = 0; i < bootPkgs.length; i++ )
            {

                // A wildcarded boot delegation package will be in the form of
                // "foo.", so if the package is wildcarded do a startsWith() or
                // a regionMatches() to ignore the trailing "." to determine if
                // the request should be delegated to the parent class loader.
                // If the package is not wildcarded, then simply do an equals()
                // test to see if the request should be delegated to the parent
                // class loader.
                if ( ( bootPkgWildcards[i] && ( pkgName.startsWith( bootPkgs[i] ) || bootPkgs[i].regionMatches( 0,
                    pkgName, 0, pkgName.length() ) ) )
                    || ( !bootPkgWildcards[i] && bootPkgs[i].equals( pkgName ) ) )
                {
                    return true;
                }
            }
        }

        return false;
    }


    private R4Export toR4Export( ExportedPackage export )
    {
        R4Attribute version = new R4Attribute( Constants.VERSION_ATTRIBUTE, export.getVersion().toString(), false );
        return new R4Export( export.getName(), null, new R4Attribute[]
            { version } );
    }


    private String getBundleDescriptor( Bundle bundle )
    {
        StringBuffer val = new StringBuffer();
        if ( bundle.getSymbolicName() != null )
        {
            // list the bundle name if not null
            val.append( bundle.getSymbolicName() );
            val.append( " (" ).append( bundle.getBundleId() );
            val.append( ")" );
        }
        else if ( bundle.getLocation() != null )
        {
            // otherwise try the location
            val.append( bundle.getLocation() );
            val.append( " (" ).append( bundle.getBundleId() );
            val.append( ")" );
        }
        else
        {
            // fallback to just the bundle id
            // only append the bundle
            val.append( bundle.getBundleId() );
        }
        return val.toString();
    }


    private void refresh( final Bundle bundle )
    {
        getPackageAdmin().refreshPackages( new Bundle[]
            { bundle } );
    }
}
