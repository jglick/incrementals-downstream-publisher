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
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.yaml.snakeyaml.Yaml;

public class Main {

    public static void main(String[] argv) throws Exception {
        String buildURL = System.getenv("BUILD_URL");
        String jenkinsURL = System.getenv("JENKINS_URL");
        if (buildURL == null || jenkinsURL == null) {
            throw new IllegalStateException("Run this inside Jenkins so $BUILD_URL & $JENKINS_URL are set");
        }
        String rpu = System.getenv("RPU");
        if (rpu == null) {
            throw new IllegalStateException("Define $RPU to point to a checkout of jenkins-infra/repository-permissions-updater");
        }

        DocumentContext data = JsonPath.parse(new URL(buildURL + "api/json?tree=actions[causes[upstreamUrl,upstreamProject,upstreamBuild]]"));
        String upstreamUrl = data.read("$.actions[0].causes[0].upstreamUrl");
        System.out.println(upstreamUrl);
        String upstreamProject = data.read("$.actions[0].causes[0].upstreamProject");
        System.out.println(upstreamProject);
        int upstreamBuild = data.read("$.actions[0].causes[0].upstreamBuild");
        System.out.println(upstreamBuild);

        // TODO also match Jenkins core etc.
        // TODO this does not work for structs: we need to parse plugin-structs.yml + pom-structs-parent.yml + component-symbol-annotation.yml
        Matcher m = Pattern.compile("(.+/)?([^/]+)-plugin/([^/]+)").matcher(upstreamProject);
        if (!m.matches()) {
            throw new IllegalStateException("Did not recognize " + upstreamProject);
        }
        String name = m.group(2);
        File defn = new File(rpu, "permissions/plugin-" + name + ".yml");
        if (!defn.isFile()) {
            throw new IllegalStateException("Could not find " + defn);
        }
        List<String> paths;
        try (InputStream is = new FileInputStream(defn)) {
            Map<String, List<String>> yaml = new Yaml().load(is);
            paths = yaml.get("paths");
        }
        System.out.println(paths);

        data = JsonPath.parse(new URL(jenkinsURL + upstreamUrl + upstreamBuild + "/api/json?tree=actions[revision[hash,pullHash]]"));
        List<Map<String, String>> hashes = data.read("$.actions[*].revision.['hash', 'pullHash']");
        String hash = hashes.get(0).entrySet().iterator().next().getValue().substring(0, 12);
        System.out.println(hash);

        URL zip = new URL(jenkinsURL + upstreamUrl + upstreamBuild + "/artifact/**/*-rc*." + hash + "/*-rc*." + hash + "*/*zip*/archive.zip");
        File download = File.createTempFile("download", ".zip");
        //download.deleteOnExit();
        FileUtils.copyURLToFile(zip, download);
        System.out.println(download);

        try (ZipFile downloadZ = new ZipFile(download)) {
            Enumeration<? extends ZipEntry> entries = downloadZ.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (!paths.stream().anyMatch(path -> entry.startsWith(path + "/"))) {
                    System.out.println("illegal: " + entry);
                }
            }
        }
    }

}
