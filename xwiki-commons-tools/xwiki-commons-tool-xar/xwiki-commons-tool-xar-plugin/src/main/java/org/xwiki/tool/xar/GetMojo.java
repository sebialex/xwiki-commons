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
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
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
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * TODO.
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
    private static final String PACKAGING_TYPE = "xar";

    /**
     * If false then don't pretty print the XML.
     */
    @Parameter(property = "url", readonly = true)
    private URL url = new URL("http://localhost:8080/xwiki/bin/");

    /**
     * If false then don't pretty print the XML.
     */
    @Parameter(property = "space", readonly = true, required = true)
    private String space;

    /**
     * If false then don't pretty print the XML.
     */
    @Parameter(property = "user", readonly = true)
    private String user;

    /**
     * If false then don't pretty print the XML.
     */
    @Parameter(property = "pass", readonly = true)
    private String pass;

    /**
     * If false then don't pretty print the XML.
     */
    @Parameter(property = "override", readonly = true)
    private boolean override;

    private File workDir = new File("target/xwiki-xar-plugin-get");

    private String [] excludes;


    /**
     * Empty constructor for catching the MalformedURLException for the default url.
     * @throws MalformedURLException if default url is malformed
     */
    public GetMojo() throws MalformedURLException
    {
        List<String> list = new ArrayList<>();
        list.addAll(Arrays.asList(super.getExcludes()));
        list.add("package.xml");
        this.excludes = list.toArray(new String[list.size()]);
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        this.setup();

        getLog().info(String.format("Getting XAR XML files from [%s]...", this.url));

        try {

            XarPomFinder xarPomFinder = new XarPomFinder();

            Files.walkFileTree(new File("./").toPath(), xarPomFinder);

            for (Path pomPath : xarPomFinder.getPoms()) {

                XMLFinder xmlFinder = new XMLFinder();

                Path pomParent = pomPath.getParent();
                File resourcesDir = new File(pomParent.toFile(), "src/main/resources/");

                if (resourcesDir.exists() && resourcesDir.isDirectory()) {
                    Files.walkFileTree(resourcesDir.toPath(), xmlFinder);
                }

                for (Path xmlPath : xmlFinder.getXmlFiles()) {
                    getLog().info(this.handleXML(xmlPath, resourcesDir).toString());
                }
            }

        } catch (IOException e) {
            throw new MojoExecutionException(String.format("Error encountered: [%s]", e.getMessage()), e);
        } finally {
            this.clean();
        }
    }

    @Override
    protected String[] getExcludes() {
        return this.excludes;
    }

    private StringBuilder handleXML(Path xmlPath, File resourcesDir) throws IOException, MojoExecutionException
    {
        File xmlFile = xmlPath.toFile();

        StringBuilder buffer = new StringBuilder();
        buffer.append(String.format("  Getting [%s/%s]... ", xmlFile.getParentFile().getName(), xmlFile.getName()));

        if (!this.override && xmlFile.length() != 0) {
            buffer.append("skipping (file not empty and override not enabled)");
            return buffer;
        }

        File xarFile = this.getXarFile(xmlPath);

        if (xarFile.length() <= 0) {
            buffer.append("skipping (xar is empty)");
            return buffer;
        }

        super.unpack(xarFile, resourcesDir, "XAR Plugin", true, getIncludes(), this.getExcludes());

        File packageXMLFile = new File(resourcesDir, "package.xml");
        if (packageXMLFile.exists()) {
            FileUtils.deleteQuietly(packageXMLFile);
        }

        buffer.append("ok");
        return buffer;
    }

    private File getXarFile(Path xmlPath) throws IOException
    {
        URL xarUrl = this.getURL(xmlPath);
        File xarFile = new File(this.workDir, "temp" + xmlPath.hashCode() + ".xar");

        URLConnection urlConnection;
        InputStream in = null;


        try (OutputStream out = new BufferedOutputStream(FileUtils.openOutputStream(xarFile))) {
            urlConnection = xarUrl.openConnection();

            if (StringUtils.isNotBlank(this.user) && StringUtils.isNotBlank(this.pass)) {
                byte[] credentials = String.format("%s:%s", this.user, this.pass).getBytes(StandardCharsets.UTF_8);
                String basicAuth = "Basic " +  new String(Base64.getUrlEncoder().encode(credentials));
                urlConnection.setRequestProperty("Authorization", basicAuth);
            }

            //getLog().info(String.format("--- Connection opened to [%s]", urlConnection.getURL()));

            //int responseCode = httpConnection.getResponseCode();

           /* if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException(String.format("Could not connect to [%s], code=[%s], message=[%s]",
                    this.url, responseCode,httpConnection.getResponseMessage()));
            }*/

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
        String pageName  = StringUtils.removeEndIgnoreCase(path.toFile().getName(), ".xml");
        String completePageName = this.space + "." + pageName;
        String urlSuffix = "/export/" + this.space + "/" + pageName + "?format=xar&name=" +
            completePageName + "&pages=xwiki%3A" + completePageName;
        return new URL(this.url, urlSuffix);
    }

    private void setup() throws MojoExecutionException
    {
        this.clean();
        try {
            FileUtils.forceMkdir(this.workDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Could not create plugin temp working directory: " + this.workDir, e);
        }
    }

    private void clean()
    {
        if (this.workDir.exists()) {
            FileUtils.deleteQuietly(this.workDir);
        }
    }

    /**
     * TODO
     */
    public static class XMLFinder extends SimpleFileVisitor<Path>
    {

        private final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:*.xml");

        private final List<Path> xmlFiles = new LinkedList<>();

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException {

            Path name = file.getFileName();
            if (!attr.isRegularFile() || name == null || !this.matcher.matches(name)) {
                return FileVisitResult.CONTINUE;
            }

            this.xmlFiles.add(file);

            return FileVisitResult.CONTINUE;
        }

        /**
         * Getter for xmlFiles
         *
         * @return xmlFiles
         */
        public List<Path> getXmlFiles()
        {
            return xmlFiles;
        }
    }

    /**
     * TODO
     */
    public static class XarPomFinder extends SimpleFileVisitor<Path>
    {
        private final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:pom.xml");

        private final List<Path> poms = new LinkedList<>();

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attr) throws IOException
        {
            Path name = file.getFileName();
            if (!attr.isRegularFile() || name == null || !this.matcher.matches(name)) {
                return FileVisitResult.CONTINUE;
            }

            File pomFile = file.toFile();

            try (FileReader reader = new FileReader(pomFile)) {
                if (StringUtils.equals(PACKAGING_TYPE, new MavenXpp3Reader().read(reader).getPackaging())) {
                    this.poms.add(file);
                }
            } catch(FileNotFoundException | XmlPullParserException ex) {
                throw new IOException("Error visiting this file: " + pomFile, ex);
            }

            return FileVisitResult.CONTINUE;
        }

        /**
         * Getter for poms
         *
         * @return poms
         */
        public List<Path> getPoms()
        {
            return poms;
        }
    }
}
