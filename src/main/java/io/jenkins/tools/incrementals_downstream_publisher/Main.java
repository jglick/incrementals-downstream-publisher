/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.tools.incrementals_downstream_publisher;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.io.File;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.io.FileUtils;

public class Main {

    public static void main(String[] argv) throws Exception {
        String buildURL = System.getenv("BUILD_URL");
        String jenkinsURL = System.getenv("JENKINS_URL");
        if (buildURL == null || jenkinsURL == null) {
            throw new IllegalStateException("Run this inside Jenkins so $BUILD_URL & $JENKINS_URL are set");
        }
        String downloadN = System.getenv("DOWNLOAD");
        if (downloadN == null) {
            throw new IllegalStateException("Specify $DOWNLOAD");
        }
        URL downstreamMetadata = new URL(buildURL + "api/json?tree=actions[causes[upstreamUrl,upstreamBuild]]");
        System.out.println("Parsing: " + downstreamMetadata);
        DocumentContext data = JsonPath.parse(downstreamMetadata);
        String upstreamUrl = data.read("$.actions[0].causes[0].upstreamUrl");
        /* currently unused:
        String upstreamProject = data.read("$.actions[0].causes[0].upstreamProject");
        */
        int upstreamBuild = data.read("$.actions[0].causes[0].upstreamBuild");
        URL upstreamMetadata = new URL(jenkinsURL + upstreamUrl + upstreamBuild + "/api/json?tree=actions[revision[hash,pullHash]]");
        System.out.println("Parsing: " + upstreamMetadata);
        data = JsonPath.parse(upstreamMetadata);
        List<Map<String, String>> hashes = data.read("$.actions[*].revision.['hash', 'pullHash']");
        String hash = hashes.get(0).entrySet().iterator().next().getValue().substring(0, 12);
        System.out.println("Commit hash: " + hash);
        URL zip = new URL(jenkinsURL + upstreamUrl + upstreamBuild + "/artifact/**/*-rc*." + hash + "/*-rc*." + hash + "*/*zip*/archive.zip");
        File download = new File(downloadN);
        FileUtils.copyURLToFile(zip, download);
        System.out.println("Artifacts: " + download);
        try (ZipFile zf = new ZipFile(download)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                System.out.println("Entry: " + entries.nextElement().getName());
            }
        }
    }

}
