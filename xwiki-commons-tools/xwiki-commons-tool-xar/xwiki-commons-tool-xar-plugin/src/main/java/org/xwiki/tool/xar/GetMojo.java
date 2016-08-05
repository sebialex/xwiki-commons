/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.tool.xar;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import com.google.common.collect.Lists;

/**
 * This Mojo looks in the src/main/resources/ folder for any .xml files and will attempt to connect to the configured
 * url (http://localhost:8080/xwiki/bin/ by default) and export any pages that match the xml file name. The space is
 * determined from the folder hierarchy below the resources/ folder. So for src/main/resources/X/Y/UIX_a.xml
 * it will attempt to call:
 *
 * http://localhost:8080/xwiki/bin/export/X/Y/UIX_a?format=xar&name=X.Y.UIX_a&pages=xwiki%3AX.Y.UIX_a
 *
 * An include filter determines what files paths to include in the src/main/resources/ search.
 *
 * @version $Id$
 */
@Mojo(
    name = "get",
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public class GetMojo extends AbstractXARMojo
{
    private static final String XAR_PACKAGING_TYPE = "xar";

    private static final String RESOURCES_FOLDER = "resources";

    private static final String SOURCE_BASE_PATH = "src/main/resources/";

    /**
     * Base url to use for exporting files
     */
    @Parameter(property = "url", readonly = true)
    private URL url;

    /**
     * The User to use for authentication with the server.
     */
    @Parameter(property = "user", readonly = true)
    private String user;

    /**
     * The Password to use for authentication with the server.
     */
    @Parameter(property = "pass", readonly = true)
    private String pass;

    /**
     * Regex for including files in the src/main/resources/ search
     */
    @Parameter(property = "include", readonly = true)
    private String includeFilter;

    /**
     * Base temp folder where to download the xar files.
     */
    private File workDir = new File("target/xwiki-xar-plugin-get");

    /**
     * Empty constructor for catching the MalformedURLException for the default url.
     *
     * @throws MalformedURLException if default url is malformed
     */
    public GetMojo() throws MalformedURLException
    {
        this.url = new URL("http://localhost:8080/xwiki/bin/");
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        if (!getProject().getPackaging().equals(XAR_PACKAGING_TYPE)) {
            getLog().info("Not a XAR module, skipping...");
            return;
        }

        getLog().info(String.format("Getting XAR XML files from [%s]...", this.url));

        this.setup();

        try {
            XMLFinder xmlFinder = new XMLFinder(this.includeFilter);

            Path pomParent =  getProject().getBasedir().toPath();
            File resourcesDir = new File(pomParent.toFile(), SOURCE_BASE_PATH);

            if (resourcesDir.exists() && resourcesDir.isDirectory()) {
                Files.walkFileTree(resourcesDir.toPath(), xmlFinder);
            }

            for (Path xmlPath : xmlFinder.getPaths()) {
                getLog().info(this.handleXML(xmlPath, resourcesDir).toString());
            }

        } catch (IOException e) {
            throw new MojoExecutionException(String.format("Error encountered: [%s]", e.getMessage()), e);
        } finally {
            this.clean();
        }
    }

    private StringBuilder handleXML(Path xmlPath, File resourcesDir) throws IOException, MojoExecutionException
    {
        File xmlFile = xmlPath.toFile();

        StringBuilder buffer = new StringBuilder();
        buffer.append(String.format("  Getting [%s/%s]... ", xmlFile.getParentFile().getName(), xmlFile.getName()));

        File xarFile = this.getXarFile(xmlPath);

        if (xarFile == null || xarFile.length() <= 0) {
            buffer.append("skipping (xar is empty)");
            return buffer;
        }

        super.unpack(xarFile, resourcesDir, "XAR Plugin", true, new String[]{"**/" + xmlFile.getName()}, getExcludes());

        buffer.append("ok");
        return buffer;
    }

    private File getXarFile(Path xmlPath) throws IOException
    {
        URL xarUrl = this.getURL(xmlPath);
        File xarFile = new File(this.workDir, "temp" + xmlPath.hashCode() + ".xar");

        HttpURLConnection urlConnection;
        InputStream in = null;


        try (OutputStream out = new BufferedOutputStream(FileUtils.openOutputStream(xarFile))) {
            urlConnection = (HttpURLConnection)xarUrl.openConnection();

            if (StringUtils.isNotBlank(this.user) && StringUtils.isNotBlank(this.pass)) {
                byte[] credentials = String.format("%s:%s", this.user, this.pass).getBytes(StandardCharsets.UTF_8);
                String basicAuth = "Basic " + new String(Base64.getEncoder().encode(credentials), StandardCharsets.UTF_8);
                urlConnection.setRequestProperty("Authorization", basicAuth);
            }

            int responseCode = urlConnection.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_OK) {
                if (responseCode == HttpURLConnection.HTTP_NOT_FOUND ||
                    responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
                    return null;
                }
                else {
                    throw new IOException(String.format("Could not connect to [%s], code=[%s], message=[%s]",
                        this.url, responseCode, urlConnection.getResponseMessage()));
                }
            }

            if (getLog().isDebugEnabled()) {
                getLog().debug("   url=" + xarUrl);
            }

            in = urlConnection.getInputStream();
            IOUtils.copy(in, out);
        }
        finally {
            IOUtils.closeQuietly(in);
        }

        return xarFile;
    }

    private URL getURL(Path path) throws MalformedURLException
    {
        List<String> spaces = getSpaces(path);
        String spacesURL =  StringUtils.join(spaces, "/");
        String spacesName =  StringUtils.join(spaces, ".");
        String pageName  = StringUtils.removeEndIgnoreCase(path.toFile().getName(), ".xml");
        String completePageName = spacesName + "." + pageName;

        String urlSuffix = "/export/" + spacesURL + "/" + pageName + "?format=xar&name=" + completePageName +
            "&pages=xwiki%3A" + completePageName + "&outputSyntax=plain";
        return new URL(this.url, urlSuffix);
    }

    private List<String> getSpaces(Path path)
    {
        List<String> result = new LinkedList<>();

        Path parent = path.getParent();
        String parentName;
        while (parent != null && !StringUtils.equals(parentName = parent.toFile().getName(), RESOURCES_FOLDER)) {
            result.add(parentName);
            parent = parent.getParent();
        }

        return Lists.reverse(result);
    }

    private void setup() throws MojoExecutionException
    {
        this.clean();
        try {
            FileUtils.forceMkdir(this.workDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not create plugin temp working directory: " + this.workDir, e);
        }

        List<String> list = new ArrayList<>();
        list.addAll(Arrays.asList(getExcludes()));
        list.add(AbstractXARMojo.PACKAGE_XML);
        super.excludes = list.toArray(new String[list.size()]);
    }

    private void clean()
    {
        if (this.workDir.exists()) {
            FileUtils.deleteQuietly(this.workDir);
        }
    }

    /**
     * File visitor for finding xml files.
     */
    public static class XMLFinder extends SimpleFileVisitor<Path>
    {
        private final PathMatcher matcher;

        private final List<Path> paths = new LinkedList<>();

        /**
         * Constructor.
         * @param includeFilter regex to use for file search
         */
        public XMLFinder(String includeFilter)
        {
            String regexToUse = ".*";

            if (includeFilter != null) {
                regexToUse = includeFilter;
            }

            String matcherPattern = "regex:" + regexToUse + ".xml";

            this.matcher = FileSystems.getDefault().getPathMatcher(matcherPattern);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException {

            if (!attr.isRegularFile() || !this.matcher.matches(file.toAbsolutePath())) {
                return FileVisitResult.CONTINUE;
            }

            this.paths.add(file);

            return FileVisitResult.CONTINUE;
        }

        /**
         * Getter for paths
         *
         * @return paths
         */
        public List<Path> getPaths()
        {
            return paths;
        }
    }
}
