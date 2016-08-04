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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;

/**
 * Unit tests for {@link org.xwiki.tool.xar.GetMojo}.
 *
 * @version $Id$
 */
public class GetMojoTest
{

    @Test
    public void test2() {
        byte[] credentials1 = String.format("[%s]:[%s]", "Admin", "admin").getBytes(StandardCharsets.UTF_8);
        byte[] credentials2 = ("Admin" + ":" + "admin").getBytes(StandardCharsets.UTF_8);
        String basicAuth1 = "Basic " +  new String(Base64.getEncoder().encode(credentials1));
        String basicAuth2 = "Basic " +  new String(Base64.getEncoder().encode(credentials2));
        System.out.println("--- xml=" + String.format("basicAuth1=[%s]", basicAuth1));
        System.out.println("--- xml=" + String.format("basicAuth2=[%s]", basicAuth2));
    }

    @Test
    public void test13() throws IOException {
        File file = new File("/home/sebastian/gene42/git/phenotips-niaid-fork");
    }

}
